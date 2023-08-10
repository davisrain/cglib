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
package net.sf.cglib.proxy;

import java.lang.reflect.Method;
import java.util.*;
import net.sf.cglib.core.*;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

class MethodInterceptorGenerator
implements CallbackGenerator
{
    public static final MethodInterceptorGenerator INSTANCE = new MethodInterceptorGenerator();

    static final String EMPTY_ARGS_NAME = "CGLIB$emptyArgs";
    static final String FIND_PROXY_NAME = "CGLIB$findMethodProxy";
    static final Class[] FIND_PROXY_TYPES = { Signature.class };

    private static final Type ABSTRACT_METHOD_ERROR =
      TypeUtils.parseType("AbstractMethodError");
    private static final Type METHOD =
      TypeUtils.parseType("java.lang.reflect.Method");
    private static final Type REFLECT_UTILS =
      TypeUtils.parseType("net.sf.cglib.core.ReflectUtils");
    private static final Type METHOD_PROXY =
      TypeUtils.parseType("net.sf.cglib.proxy.MethodProxy");
    private static final Type METHOD_INTERCEPTOR =
      TypeUtils.parseType("net.sf.cglib.proxy.MethodInterceptor");
    private static final Signature GET_DECLARED_METHODS =
      TypeUtils.parseSignature("java.lang.reflect.Method[] getDeclaredMethods()");
    private static final Signature GET_DECLARING_CLASS =
      TypeUtils.parseSignature("Class getDeclaringClass()");
    private static final Signature FIND_METHODS =
      TypeUtils.parseSignature("java.lang.reflect.Method[] findMethods(String[], java.lang.reflect.Method[])");
    private static final Signature MAKE_PROXY =
      new Signature("create", METHOD_PROXY, new Type[]{
          Constants.TYPE_CLASS,
          Constants.TYPE_CLASS,
          Constants.TYPE_STRING,
          Constants.TYPE_STRING,
          Constants.TYPE_STRING
      });
    private static final Signature INTERCEPT =
      new Signature("intercept", Constants.TYPE_OBJECT, new Type[]{
          Constants.TYPE_OBJECT,
          METHOD,
          Constants.TYPE_OBJECT_ARRAY,
          METHOD_PROXY
      });
    private static final Signature FIND_PROXY =
      new Signature(FIND_PROXY_NAME, METHOD_PROXY, new Type[]{ Constants.TYPE_SIGNATURE });
    private static final Signature TO_STRING =
      TypeUtils.parseSignature("String toString()");
    private static final Transformer METHOD_TO_CLASS = new Transformer(){
        public Object transform(Object value) {
            return ((MethodInfo)value).getClassInfo();
        }
    };
    private static final Signature CSTRUCT_SIGNATURE =
        TypeUtils.parseConstructor("String, String");

    private String getMethodField(Signature impl) {
        return impl.getName() + "$Method";
    }
    private String getMethodProxyField(Signature impl) {
        return impl.getName() + "$Proxy";
    }

    public void generate(ClassEmitter ce, Context context, List methods) {
        // 创建一个map，key为方法签名的string格式，value为methodProxyField
        Map sigMap = new HashMap();
        // 遍历传入的需要由该callbackGenerator生成的方法集合
        for (Iterator it = methods.iterator(); it.hasNext();) {
            // 获取到对应方法的MethodInfo
            MethodInfo method = (MethodInfo)it.next();
            // 获取到方法签名
            Signature sig = method.getSignature();
            // 然后获取到要实现的方法的签名，也就是代理方法的签名，逻辑就是将方法名进行了替换
            // 替换规则是implName = CGLIB$ + {methodName} + $ + {index}(表示在所有需要被代理的方法中的位置)
            Signature impl = context.getImplSignature(method);

            // 获取指向被代理方法的反射对象Method的字段名，命名规则是implName + $Method
            String methodField = getMethodField(impl);
            // 获取指向被代理方法的代理对象MethodProxy的字段名，命名规则是implName + $Proxy
            String methodProxyField = getMethodProxyField(impl);

            // 将前面的string作为key，methodProxyField作为value放入map中
            sigMap.put(sig.toString(), methodProxyField);
            // 然后在代理类中声明一个 private static final Method CGLIB${methodName}${index}$Method 字段
            ce.declare_field(Constants.PRIVATE_FINAL_STATIC, methodField, METHOD, null);
            // 在代理类中声明一个 private static final MethodProxy CGLIB${methodName}${index}$Proxy 字段
            ce.declare_field(Constants.PRIVATE_FINAL_STATIC, methodProxyField, METHOD_PROXY, null);
            // 在代理类中声明一个 private static final Object[] CGLIB$emptyArgs 字段
            ce.declare_field(Constants.PRIVATE_FINAL_STATIC, EMPTY_ARGS_NAME, Constants.TYPE_OBJECT_ARRAY, null);
            CodeEmitter e;

            // access method
            // 在代理类中根据impl方法签名声明一个final修饰的，方法名是CGLIB$开头的方法
            e = ce.begin_method(Constants.ACC_FINAL,
                                impl,
                                method.getExceptionTypes());
            // 向上一步声明的方法的code中添加调用父类同签名方法的逻辑
            superHelper(e, method, context);
            // 然后向code中添加return相关的字节码
            e.return_value();
            e.end_method();

            // around method
            // 调用context的beginMethod方法，将classEmitter和methodInfo都传入
            // 逻辑是在代理类中声明一个由method原本的签名的方法
            e = context.beginMethod(ce, method);
            // 创建一个label
            Label nullInterceptor = e.make_label();
            // 调用context获取方法对应的callback的index
            // 然后向该方法的code中添加获取指向callback引用的字段的字节码
            context.emitCallback(e, context.getIndex(method));
            // 添加dup字节码，复制栈顶元素，此时的栈顶元素是Callback
            e.dup();
            // 如果栈顶元素是null，跳到nullInterceptor标签
            e.ifnull(nullInterceptor);

            // 如果栈顶元素不为null，添加aload_0字节码
            e.load_this();
            // 添加getstatic字节码，获取CGLIB${methodName}${index}$Method字段
            e.getfield(methodField);

            // 如果签名的参数长度为0
            if (sig.getArgumentTypes().length == 0) {
                // 添加getstatic字节码，获取CGLIB$emptyArgs字段
                e.getfield(EMPTY_ARGS_NAME);
            } else {
                // 否则创建参数数组
                e.create_arg_array();
            }

            // 添加getstatic字节码，获取CGLIB${methodName}${index}$Proxy字段
            e.getfield(methodProxyField);
            // 添加invokeinterface字节码，调用MethodInterceptor接口的interceptor方法
            e.invoke_interface(METHOD_INTERCEPTOR, INTERCEPT);
            // 根据方法签名的返回值类型进行拆箱或强转
            e.unbox_or_zero(sig.getReturnType());
            // 然后添加return相关的字节码返回
            e.return_value();

            // 将nullInterceptor标签标记在这里
            // 如果callback字段为null的话，跳转到这里执行
            e.mark(nullInterceptor);
            // 向code中添加调用父类相同签名方法的逻辑
            superHelper(e, method, context);
            // 然后添加return相关的字节码
            e.return_value();
            // 结束方法，计算方法的max_locals max_stacks
            e.end_method();
        }
        // 生成findProxy方法
        generateFindProxy(ce, sigMap);
    }

    private static void superHelper(CodeEmitter e, MethodInfo method, Context context)
    {
        // 如果方法的修饰符是abstract的，向代理方法的code中添加一句throw AbstractMethodError的字节码
        if (TypeUtils.isAbstract(method.getModifiers())) {
            e.throw_exception(ABSTRACT_METHOD_ERROR, method.toString() + " is abstract" );
        }
        // 如果不是抽象的
        else {
            // 向代理方法的code中添加aload_0字节码，加载this引用
            e.load_this();
            // 然后调用context的emitLoadArgsAndInvoke方法
            context.emitLoadArgsAndInvoke(e, method);
        }
    }

    public void generateStatic(CodeEmitter e, Context context, List methods) throws Exception {
        /* generates:
           static {
             Class thisClass = Class.forName("NameOfThisClass");
             Class cls = Class.forName("java.lang.Object");
             String[] sigs = new String[]{ "toString", "()Ljava/lang/String;", ... };
             Method[] methods = cls.getDeclaredMethods();
             methods = ReflectUtils.findMethods(sigs, methods);
             METHOD_0 = methods[0];
             CGLIB$ACCESS_0 = MethodProxy.create(cls, thisClass, "()Ljava/lang/String;", "toString", "CGLIB$ACCESS_0");
             ...
           }
        */

        // 向CGLIB$STATCKHOOK方法中插入iconst_0字节码
        e.push(0);
        // 然后插入newarray字节码，表示生成一个长度为0的Object数组
        e.newarray();
        // 然后插入putstatic字节码，将数组复制给CGLIB$emptyArgs属性
        e.putfield(EMPTY_ARGS_NAME);

        // 创建一个Object类型的local变量，用于表示thisclass
        Local thisclass = e.make_local();
        // 创建一个Object类型的local变量，用于表示declaringclass
        Local declaringclass = e.make_local();
        // 向code中添加invokestatic字节码，调用Class.forName方法加载当前类
        EmitUtils.load_class_this(e);
        // 然后添加astore字节码，将栈顶的class对象存入到thisclass这个局部变量slot中
        e.store_local(thisclass);

        // 将传入的methods这个list转换为Map，其中key为methodInfo对应的classInfo，value为MethodInfo的集合
        // 也就是<ClassInfo, List<MethodInfo>>
        Map methodsByClass = CollectionUtils.bucket(methods, METHOD_TO_CLASS);
        // 遍历map
        for (Iterator i = methodsByClass.keySet().iterator(); i.hasNext();) {
            // 获取classInfo
            ClassInfo classInfo = (ClassInfo)i.next();

            // 获取ClassInfo对应的MethodInfo集合
            List classMethods = (List)methodsByClass.get(classInfo);
            // 向栈顶插入classMethod集合大小的两倍的数值
            e.push(2 * classMethods.size());
            // 然后根据这个长度创建一个String数组
            e.newarray(Constants.TYPE_STRING);
            // 遍历MethodInfo集合
            for (int index = 0; index < classMethods.size(); index++) {
                MethodInfo method = (MethodInfo) classMethods.get(index);
                // 获取method对应的方法签名
                Signature sig = method.getSignature();
                // 复制栈顶元素，即复制String数组的引用
                e.dup();
                // 将index*2 和 方法名依次压入栈顶
                e.push(2 * index);
                e.push(sig.getName());
                // 使用aastore将方法名存入对应下标的数组元素中
                e.aastore();
                // 继续复制String数组
                e.dup();
                // 将index * 2 + 1  和 方法的描述符压入栈顶
                e.push(2 * index + 1);
                e.push(sig.getDescriptor());
                // 使用aastore将方法描述符存入对应数组下标中
                e.aastore();
            }

            // 加载classInfo对应的类型，即调用Class.forName方法
            EmitUtils.load_class(e, classInfo.getType());
            // 复制栈顶元素
            e.dup();
            // 将其存入到declaringclass这个局部遍历slot中
            e.store_local(declaringclass);
            // 调用Class的getDeclaredMethods方法
            e.invoke_virtual(Constants.TYPE_CLASS, GET_DECLARED_METHODS);
            // 然后再调用ReflectUtils的findMethods方法，其中参数为之前声明的String数组和declaringClass的declaredMethods数组
            // 到这一步就根据方法名和描述符找到了对应的方法的反射对应Method数组
            e.invoke_static(REFLECT_UTILS, FIND_METHODS);

            // 然后遍历MethodInfo集合
            for (int index = 0; index < classMethods.size(); index++) {
                MethodInfo method = (MethodInfo) classMethods.get(index);
                // 获取方法签名
                Signature sig = method.getSignature();
                // 获取实现方法的签名
                Signature impl = context.getImplSignature(method);
                // 将栈顶的Method类型的数组复制
                e.dup();
                // 将index压入栈顶
                e.push(index);
                // 然后添加aaload字节码，读取Method数组对应index下标的元素
                e.array_load(METHOD);
                // 然后添加putstatic 放入到private static final Method CGLIB$ + {methodName} + $ + {index} + $Method字段中
                e.putfield(getMethodField(impl));

                // 加载局部变量declaingclass到操作数栈
                e.load_local(declaringclass);
                // 加载局部遍历thisclass到操作数栈顶
                e.load_local(thisclass);
                // 然后将方法的描述符 和 方法名 还有实现方法的方法名依次压入操作数栈顶
                e.push(sig.getDescriptor());
                e.push(sig.getName());
                e.push(impl.getName());
                // 调用添加invokestatic，调用MethodProxy的create方法创建MethodProxy对象
                e.invoke_static(METHOD_PROXY, MAKE_PROXY);
                // 然后添加putstatic字节码，将MethodProxy对象放入到private static final MethodProxy CGLIB$ + {methodName} + $ + {index} + $Proxy字段中
                e.putfield(getMethodProxyField(impl));
            }
            // 添加pop字节码，将操作数栈顶的Method数组出栈
            e.pop();
        }
    }

    public void generateFindProxy(ClassEmitter ce, final Map sigMap) {
        // 在类中声明一个public static MethodProxy CGLIB$findMethodProxy(Signature s)方法
        final CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC | Constants.ACC_STATIC,
                                              FIND_PROXY,
                                              null);
        // 添加字节码加载第一个参数，因为是静态方法，所以第一次参数就是Signature
        e.load_arg(0);
        // 调用Signature的toString方法
        e.invoke_virtual(Constants.TYPE_OBJECT, TO_STRING);
        ObjectSwitchCallback callback = new ObjectSwitchCallback() {
            public void processCase(Object key, Label end) {
                e.getfield((String)sigMap.get(key));
                e.return_value();
            }
            public void processDefault() {
                e.aconst_null();
                e.return_value();
            }
        };
        EmitUtils.string_switch(e,
                                (String[])sigMap.keySet().toArray(new String[0]),
                                Constants.SWITCH_STYLE_HASH,
                                callback);
        e.end_method();
    }
}
