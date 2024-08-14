/*
 * Copyright 2003,2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.cglib.reflect;

import java.lang.reflect.*;
import java.util.*;
import net.sf.cglib.core.*;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
    
class FastClassEmitter extends ClassEmitter {
    private static final Signature CSTRUCT_CLASS =
      TypeUtils.parseConstructor("Class");
    private static final Signature METHOD_GET_INDEX =
      TypeUtils.parseSignature("int getIndex(String, Class[])");
    private static final Signature SIGNATURE_GET_INDEX =
      new Signature("getIndex", Type.INT_TYPE, new Type[]{ Constants.TYPE_SIGNATURE });
    private static final Signature TO_STRING =
      TypeUtils.parseSignature("String toString()");
    private static final Signature CONSTRUCTOR_GET_INDEX =
      TypeUtils.parseSignature("int getIndex(Class[])");
    private static final Signature INVOKE =
      TypeUtils.parseSignature("Object invoke(int, Object, Object[])");
    private static final Signature NEW_INSTANCE =
      TypeUtils.parseSignature("Object newInstance(int, Object[])");
    private static final Signature GET_MAX_INDEX =
      TypeUtils.parseSignature("int getMaxIndex()");
    private static final Signature GET_SIGNATURE_WITHOUT_RETURN_TYPE =
      TypeUtils.parseSignature("String getSignatureWithoutReturnType(String, Class[])");
    private static final Type FAST_CLASS =
      TypeUtils.parseType("net.sf.cglib.reflect.FastClass");
    private static final Type ILLEGAL_ARGUMENT_EXCEPTION =
      TypeUtils.parseType("IllegalArgumentException");
    private static final Type INVOCATION_TARGET_EXCEPTION =
      TypeUtils.parseType("java.lang.reflect.InvocationTargetException");
    private static final Type[] INVOCATION_TARGET_EXCEPTION_ARRAY = { INVOCATION_TARGET_EXCEPTION };
    
    public FastClassEmitter(ClassVisitor v, String className, Class type) {
        super(v);

        Type base = Type.getType(type);
        // 根据className创建一个类，继承FastClass，不实现任何接口
        begin_class(Constants.V1_8, Constants.ACC_PUBLIC, className, FAST_CLASS, null, Constants.SOURCE_FILE);

        // constructor
        // 声明一个带Class类型参数的<init>方法
        CodeEmitter e = begin_method(Constants.ACC_PUBLIC, CSTRUCT_CLASS, null);
        // 然后调用父类的带参构造方法，然后返回
        e.load_this();
        e.load_args();
        e.super_invoke_constructor(CSTRUCT_CLASS);
        e.return_value();
        e.end_method();

        VisibilityPredicate vp = new VisibilityPredicate(type, false);
        // 获取type以及父类和接口声明的方法
        List methods = ReflectUtils.addAllMethods(type, new ArrayList());
        // 然后进行可见性过滤，将private和protected修饰的方法都过滤掉
        CollectionUtils.filter(methods, vp);
        // 然后过滤掉重复的方法，如果方法签名相同，声明在前面的方法会保留下来，后面的方法会被拒绝掉(也就是父类或接口声明的方法会被过滤掉)，
        // 但是如果子类实现的方法是一个桥接方法，用于改变父类中方法的可见性，那么子类的方法会被拒绝掉
        CollectionUtils.filter(methods, new DuplicatesPredicate());
        // 获取type中声明的构造方法
        List constructors = new ArrayList(Arrays.asList(type.getDeclaredConstructors()));
        // 将构造方法也按可见性过滤
        CollectionUtils.filter(constructors, vp);
        
        // getIndex(String)
        // 声明一个getIndex(Signature)方法，传入一个方法签名，然后返回方法签名在方法集合中的index
        emitIndexBySignature(methods);

        // getIndex(String, Class[])
        // 声明一个getIndex(String, Class[])方法，传入方法名和参数类型，返回对应方法在方法集合中的index
        emitIndexByClassArray(methods);
        
        // getIndex(Class[])
        e = begin_method(Constants.ACC_PUBLIC, CONSTRUCTOR_GET_INDEX, null);
        e.load_args();
        List info = CollectionUtils.transform(constructors, MethodInfoTransformer.getInstance());
        EmitUtils.constructor_switch(e, info, new GetIndexCallback(e, info));
        e.end_method();

        // invoke(int, Object, Object[])
        // 声明一个public Object invoke(int, Object, Object[]) throws InvocationTargetException 方法
        e = begin_method(Constants.ACC_PUBLIC, INVOKE, INVOCATION_TARGET_EXCEPTION_ARRAY);
        // 加载方法的第二个参数到栈顶
        e.load_arg(1);
        // 将对象强转为base类型
        e.checkcast(base);
        // 加载方法的第一个参数，即methodIndex
        e.load_arg(0);
        invokeSwitchHelper(e, methods, 2, base);
        e.end_method();

        // newInstance(int, Object[])
        // 声明一个public Object newInstance(int, Object[]) throws InvocationTargetException 方法
        e = begin_method(Constants.ACC_PUBLIC, NEW_INSTANCE, INVOCATION_TARGET_EXCEPTION_ARRAY);
        // new一个base对应的对象
        e.new_instance(base);
        // 复制栈顶元素
        e.dup();
        // 加载方法的第一个参数
        e.load_arg(0);
        invokeSwitchHelper(e, constructors, 1, base);
        e.end_method();

        // getMaxIndex()
        e = begin_method(Constants.ACC_PUBLIC, GET_MAX_INDEX, null);
        e.push(methods.size() - 1);
        e.return_value();
        e.end_method();

        end_class();
    }

    // TODO: support constructor indices ("<init>")
    private void emitIndexBySignature(List methods) {
        // 声明一个getIndex方法，参数类型是Signature
        CodeEmitter e = begin_method(Constants.ACC_PUBLIC, SIGNATURE_GET_INDEX, null);
        // 将传入的方法集合都转换为Signature的String集合
        List signatures = CollectionUtils.transform(methods, new Transformer() {
            public Object transform(Object obj) {
                return ReflectUtils.getSignature((Method)obj).toString();
            }
        });
        // 加载方法第一个参数到操作数栈，也就是Signature
        e.load_arg(0);
        // 调用方法签名的toString方法，得到Signature的String
        e.invoke_virtual(Constants.TYPE_OBJECT, TO_STRING);
        // 根据方法签名集合进行switch选择，找到传入的方法签名在方法集合中的index并返回
        signatureSwitchHelper(e, signatures);
        e.end_method();
    }

    private static final int TOO_MANY_METHODS = 100; // TODO
    private void emitIndexByClassArray(List methods) {
        // 声明一个getIndex方法，参数是String类型的方法名 和 Class[]类型的参数数组
        CodeEmitter e = begin_method(Constants.ACC_PUBLIC, METHOD_GET_INDEX, null);
        // 如果要选择的方法数量大于了 100，执行特殊的逻辑
        if (methods.size() > TOO_MANY_METHODS) {
            // hack for big classes
            // 将方法转换为Signature的字符串，然后只取包含方法名和参数描述符的部分，去掉返回类型的描述符
            List signatures = CollectionUtils.transform(methods, new Transformer() {
                public Object transform(Object obj) {
                    String s = ReflectUtils.getSignature((Method)obj).toString();
                    return s.substring(0, s.lastIndexOf(')') + 1);
                }
            });
            // 加载出方法的参数
            e.load_args();
            // 然后调用FastClass的getSignatureWithoutReturnType方法，将方法名和参数类型转换成signature的形式
            // 并且将结果压入栈顶
            e.invoke_static(FAST_CLASS, GET_SIGNATURE_WITHOUT_RETURN_TYPE);
            // 然后采用string类型的switch，选择出 方法名和参数描述符相等的 signature
            signatureSwitchHelper(e, signatures);
        } else {
            // 加载getIndex方法的所有参数到栈顶
            e.load_args();
            // 将Method类型的方法集合转换成MethodInfo类型的集合
            List info = CollectionUtils.transform(methods, MethodInfoTransformer.getInstance());
            EmitUtils.method_switch(e, info, new GetIndexCallback(e, info));
        }
        e.end_method();
    }

    private void signatureSwitchHelper(final CodeEmitter e, final List signatures) {
        ObjectSwitchCallback callback = new ObjectSwitchCallback() {
            public void processCase(Object key, Label end) {
                // TODO: remove linear indexOf
                // 获取String类型的key在方法签名集合中的index，然后压入操作数栈，并且返回
                e.push(signatures.indexOf(key));
                e.return_value();
            }
            public void processDefault() {
                // 返回-1
                e.push(-1);
                e.return_value();
            }
        };
        // 向方法中添加switch的逻辑，根据方法签名的hashcode进行比较，找到匹配的方法签名，然后返回其在方法签名集合中的index；
        // 如果没找到，返回-1
        EmitUtils.string_switch(e,
                                (String[])signatures.toArray(new String[signatures.size()]),
                                Constants.SWITCH_STYLE_HASH,
                                callback);
    }

    private static void invokeSwitchHelper(final CodeEmitter e, List members, final int arg, final Type base) {
        // 将Method类型的集合转换为MethodInfo类型的集合
        final List info = CollectionUtils.transform(members, MethodInfoTransformer.getInstance());
        // 创建一个illegalArg的label，用于参数验证失败后跳转
        final Label illegalArg = e.make_label();
        // 创建一个block，即包含start和end两个label
        Block block = e.begin_block();
        // 根据MethodInfo集合的数量转换为一个range数组，数组中的每个元素都作为switch的label
        e.process_switch(getIntRange(info.size()), new ProcessSwitchCallback() {
            public void processCase(int key, Label end) {
                // 命中对应的label之后
                // 获取到对应index的MethodInfo
                MethodInfo method = (MethodInfo)info.get(key);
                // 获取方法的参数类型的Type数组
                Type[] types = method.getSignature().getArgumentTypes();
                // 根据参数类型Type数组进行遍历
                for (int i = 0; i < types.length; i++) {
                    // 从方法传入的参数数组中加载对应位置的参数到栈顶，进行拆箱操作
                    e.load_arg(arg);
                    e.aaload(i);
                    e.unbox(types[i]);
                }
                // TODO: change method lookup process so MethodInfo will already reference base
                // instead of superclass when superclass method is inaccessible
                // 调用对应的方法，通过invokevirtual字节码进行调用
                e.invoke(method, base);
                // 如果方法不是构造函数，那么需要对返回类型进行装箱
                if (!TypeUtils.isConstructor(method)) {
                    e.box(method.getSignature().getReturnType());
                }
                // 执行return操作将栈顶数据返回
                e.return_value();
            }
            public void processDefault() {
                e.goTo(illegalArg);
            }
        });
        block.end();
        // 如果出现了异常，包装为InvocationTargetException抛出
        EmitUtils.wrap_throwable(block, INVOCATION_TARGET_EXCEPTION);
        e.mark(illegalArg);
        // 如果没有匹配到对应的switch的label，抛出异常
        e.throw_exception(ILLEGAL_ARGUMENT_EXCEPTION, "Cannot find matching method/constructor");
    }

    private static class GetIndexCallback implements ObjectSwitchCallback {
        private CodeEmitter e;
        private Map indexes = new HashMap();

        public GetIndexCallback(CodeEmitter e, List methods) {
            this.e = e;
            int index = 0;
            for (Iterator it = methods.iterator(); it.hasNext();) {
                indexes.put(it.next(), new Integer(index++));
            }
        }
            
        public void processCase(Object key, Label end) {
            e.push(((Integer)indexes.get(key)).intValue());
            e.return_value();
        }
        
        public void processDefault() {
            e.push(-1);
            e.return_value();
        }
    }
    
    private static int[] getIntRange(int length) {
        int[] range = new int[length];
        for (int i = 0; i < length; i++) {
            range[i] = i;
        }
        return range;
    }
}
