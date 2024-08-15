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
package net.sf.cglib.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import net.sf.cglib.core.internal.CustomizerRegistry;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

public class EmitUtils {
    // 解析参数为空的构造器的Signature，其中包含了构造器的name=<init> 以及descriptor=()V
    private static final Signature CSTRUCT_NULL =
      TypeUtils.parseConstructor("");
    private static final Signature CSTRUCT_THROWABLE =
      TypeUtils.parseConstructor("Throwable");

    private static final Signature GET_NAME =
      TypeUtils.parseSignature("String getName()");
    private static final Signature HASH_CODE =
      TypeUtils.parseSignature("int hashCode()");
    private static final Signature EQUALS =
      TypeUtils.parseSignature("boolean equals(Object)");
    private static final Signature STRING_LENGTH =
      TypeUtils.parseSignature("int length()");
    private static final Signature STRING_CHAR_AT =
      TypeUtils.parseSignature("char charAt(int)");
    private static final Signature FOR_NAME =
      TypeUtils.parseSignature("Class forName(String)");
    private static final Signature DOUBLE_TO_LONG_BITS =
      TypeUtils.parseSignature("long doubleToLongBits(double)");
    private static final Signature FLOAT_TO_INT_BITS =
      TypeUtils.parseSignature("int floatToIntBits(float)");
    private static final Signature TO_STRING =
      TypeUtils.parseSignature("String toString()");
    private static final Signature APPEND_STRING =
      TypeUtils.parseSignature("StringBuffer append(String)");
    private static final Signature APPEND_INT =
      TypeUtils.parseSignature("StringBuffer append(int)");
    private static final Signature APPEND_DOUBLE =
      TypeUtils.parseSignature("StringBuffer append(double)");
    private static final Signature APPEND_FLOAT =
      TypeUtils.parseSignature("StringBuffer append(float)");
    private static final Signature APPEND_CHAR =
      TypeUtils.parseSignature("StringBuffer append(char)");
    private static final Signature APPEND_LONG =
      TypeUtils.parseSignature("StringBuffer append(long)");
    private static final Signature APPEND_BOOLEAN =
      TypeUtils.parseSignature("StringBuffer append(boolean)");
    private static final Signature LENGTH =
      TypeUtils.parseSignature("int length()");
    private static final Signature SET_LENGTH =
      TypeUtils.parseSignature("void setLength(int)");
    private static final Signature GET_DECLARED_METHOD =
      TypeUtils.parseSignature("java.lang.reflect.Method getDeclaredMethod(String, Class[])");
     
    

    public static final ArrayDelimiters DEFAULT_DELIMITERS = new ArrayDelimiters("{", ", ", "}");

    private EmitUtils() {
    }

    public static void factory_method(ClassEmitter ce, Signature sig) {
        // 根据sig创建一个工厂方法，会创建一个MethodWriter给ClassWriter的MethodWriter链表持有，
        // 然后返回MethodWriter，给CodeEmitter的mv持有
        CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, sig, null);
        // 向method的code属性中添加new字节码，创建自身实例
        e.new_instance_this();
        // 然后向method的code中添加字节码dup 复制栈顶的元素
        e.dup();
        // 根据签名的参数类型，循环向method的code中插入对应的load字节码，加载局部变量表的参数
        e.load_args();
        // 然后根据签名的参数类型生成一个以这些参数为参数的有参构造器的Signature
        // 然后向code中插入调用自身的这个有参构造器的字节码
        e.invoke_constructor_this(TypeUtils.parseConstructor(sig.getArgumentTypes()));
        // 向method的code中添加return相关的字节码
        e.return_value();
        // 调用持有的mv的visitMaxs方法，计算max_stack max_locals 或者 stackMapFrame
        e.end_method();

        // 向method的code插入的所有字节码就相当于java源码的 return new X(arg1, arg2, ...);逻辑
    }

    public static void null_constructor(ClassEmitter ce) {
        // 调用ce的begin_method方法创建一个codeEmitter对象，
        // 其中会向ClassWriter中创建一个MethodWriter，并且该methodWriter由CodeEmitter的mv持有
        CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, CSTRUCT_NULL, null);
        // 向method的code属性中添加aload_0的字节码
        e.load_this();
        // 向method的code属性中添加invokespecial superType.<init>()V的字节码
        e.super_invoke_constructor();
        // 直接向method的code属性中添加return 字节码
        e.return_value();
        // 调用持有的mv的visitMaxs方法，计算max_stack max_locals 或者 stackMapFrame
        e.end_method();
    }
    
    /**
     * Process an array on the stack. Assumes the top item on the stack
     * is an array of the specified type. For each element in the array,
     * puts the element on the stack and triggers the callback.
     * @param type the type of the array (type.isArray() must be true)
     * @param callback the callback triggered for each element
     */
    public static void process_array(CodeEmitter e, Type type, ProcessArrayCallback callback) {
        Type componentType = TypeUtils.getComponentType(type);
        Local array = e.make_local();
        Local loopvar = e.make_local(Type.INT_TYPE);
        Label loopbody = e.make_label();
        Label checkloop = e.make_label();
        e.store_local(array);
        e.push(0);
        e.store_local(loopvar);
        e.goTo(checkloop);
        
        e.mark(loopbody);
        e.load_local(array);
        e.load_local(loopvar);
        e.array_load(componentType);
        callback.processElement(componentType);
        e.iinc(loopvar, 1);
        
        e.mark(checkloop);
        e.load_local(loopvar);
        e.load_local(array);
        e.arraylength();
        e.if_icmp(e.LT, loopbody);
    }
    
    /**
     * Process two arrays on the stack in parallel. Assumes the top two items on the stack
     * are arrays of the specified class. The arrays must be the same length. For each pair
     * of elements in the arrays, puts the pair on the stack and triggers the callback.
     * @param type the type of the arrays (type.isArray() must be true)
     * @param callback the callback triggered for each pair of elements
     */
    public static void process_arrays(CodeEmitter e, Type type, ProcessArrayCallback callback) {
        Type componentType = TypeUtils.getComponentType(type);
        Local array1 = e.make_local();
        Local array2 = e.make_local();
        Local loopvar = e.make_local(Type.INT_TYPE);
        Label loopbody = e.make_label();
        Label checkloop = e.make_label();
        e.store_local(array1);
        e.store_local(array2);
        e.push(0);
        e.store_local(loopvar);
        e.goTo(checkloop);
        
        e.mark(loopbody);
        e.load_local(array1);
        e.load_local(loopvar);
        e.array_load(componentType);
        e.load_local(array2);
        e.load_local(loopvar);
        e.array_load(componentType);
        callback.processElement(componentType);
        e.iinc(loopvar, 1);
        
        e.mark(checkloop);
        e.load_local(loopvar);
        e.load_local(array1);
        e.arraylength();
        e.if_icmp(e.LT, loopbody);
    }
    
    public static void string_switch(CodeEmitter e, String[] strings, int switchStyle, ObjectSwitchCallback callback) {
        try {
            switch (switchStyle) {
                // 根据前缀树的方式来进行switch，先根据string的长度来定位label，然后再依次根据每一个字符来进行switch
            case Constants.SWITCH_STYLE_TRIE:
                string_switch_trie(e, strings, callback);
                break;
                // 先使用string的hashcode来定位label，然后在具体的label中再根据equals来选择具体要执行的代码分支
            case Constants.SWITCH_STYLE_HASH:
                string_switch_hash(e, strings, callback, false);
                break;
                // 只根据string的hashcode来判断，不使用equals方法比较
            case Constants.SWITCH_STYLE_HASHONLY:
                string_switch_hash(e, strings, callback, true);
                break;
            default:
                throw new IllegalArgumentException("unknown switch style " + switchStyle);
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Error ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CodeGenerationException(ex);
        }
    }

    private static void string_switch_trie(final CodeEmitter e,
                                           String[] strings,
                                           final ObjectSwitchCallback callback) throws Exception {
        final Label def = e.make_label();
        final Label end = e.make_label();
        // 将方法签名的string数组转换为map，
        // key为字符串的长度，
        // value为长度等于key的这些string数组
        // Map<Integer, List<String>>
        final Map buckets = CollectionUtils.bucket(Arrays.asList(strings), new Transformer() {
            public Object transform(Object value) {
                return new Integer(((String)value).length());
            }
        });
        // 复制栈顶元素，即需要进行switch的字符串
        e.dup();
        // 调用string的length()方法获取到字符串的长度
        e.invoke_virtual(Constants.TYPE_STRING, STRING_LENGTH);
        e.process_switch(getSwitchKeys(buckets), new ProcessSwitchCallback() {
                public void processCase(int key, Label ignore_end) throws Exception {
                    List bucket = (List)buckets.get(new Integer(key));
                    stringSwitchHelper(e, bucket, callback, def, end, 0);
                }
                public void processDefault() {
                    e.goTo(def);
                }
            });
        e.mark(def);
        e.pop();
        callback.processDefault();
        e.mark(end);
    }

    private static void stringSwitchHelper(final CodeEmitter e,
                                           List strings,
                                           final ObjectSwitchCallback callback,
                                           final Label def,
                                           final Label end,
                                           final int index) throws Exception {
        final int len = ((String)strings.get(0)).length();
        // 将strings数组转换为map
        // key为string对应index的字符
        // value为index对应字符相等那些string集合
        final Map buckets = CollectionUtils.bucket(strings, new Transformer() {
            public Object transform(Object value) {
                return new Integer(((String)value).charAt(index));
            }
        });
        // 复制栈顶要switch的字符串
        e.dup();
        // 将index压入栈顶
        e.push(index);
        // 调用string的charAt方法，获取要switch的字符串index位置的字符
        e.invoke_virtual(Constants.TYPE_STRING, STRING_CHAR_AT);
        e.process_switch(getSwitchKeys(buckets), new ProcessSwitchCallback() {
                public void processCase(int key, Label ignore_end) throws Exception {
                    List bucket = (List)buckets.get(new Integer(key));
                    // 如果index + 1 等于了 要switch的字符串的长度
                    if (index + 1 == len) {
                        // 将栈顶的需要switch的字符串弹出
                        e.pop();
                        // 获取到对应的字符串，调用ObjectSwitchCallback的processCase进行匹配之后的处理逻辑
                        callback.processCase(bucket.get(0), end);
                    } else {
                        // 否则，递归调用stringSwitchHelper，并且将index + 1，匹配下一个字符
                        stringSwitchHelper(e, bucket, callback, def, end, index + 1);
                    }
                }
                public void processDefault() {
                    e.goTo(def);
                }
            });
    }        

    static int[] getSwitchKeys(Map buckets) {
        // 将buckets中的key转换为数组，并且进行排序
        int[] keys = new int[buckets.size()];
        int index = 0;
        for (Iterator it = buckets.keySet().iterator(); it.hasNext();) {
            keys[index++] = ((Integer)it.next()).intValue();
        }
        Arrays.sort(keys);
        return keys;
    }

    private static void string_switch_hash(final CodeEmitter e,
                                           final String[] strings,
                                           final ObjectSwitchCallback callback,
                                           final boolean skipEquals) throws Exception {
        // 将传入的string数组映射为hashcode，以hashcode为key，value为hashcode相等的字符串组成的集合
        // Map<Integer, List<String>>
        final Map buckets = CollectionUtils.bucket(Arrays.asList(strings), new Transformer() {
            public Object transform(Object value) {
                return new Integer(value.hashCode());
            }
        });
        // 声明两个标签
        final Label def = e.make_label();
        final Label end = e.make_label();
        // 复制栈顶元素，即要进行switch的String类型的值
        e.dup();
        // 获取该String的hashcode
        e.invoke_virtual(Constants.TYPE_OBJECT, HASH_CODE);
        // 处理switch逻辑
        e.process_switch(getSwitchKeys(buckets), new ProcessSwitchCallback() {
            public void processCase(int key, Label ignore_end) throws Exception {
                // 根据key也就是hashcode获取对应的String集合
                List bucket = (List)buckets.get(new Integer(key));
                Label next = null;
                // 如果skipEquals为true并且集合中只有一个元素
                if (skipEquals && bucket.size() == 1) {
                    // 弹出栈顶元素，即进行switch的String类型变量
                    if (skipEquals)
                        e.pop();
                    // 然后调用传入的ObjectSwitchCallback的processCase方法，将映射到的集合的第一个元素返回
                    callback.processCase((String)bucket.get(0), end);
                }
                // 如果skipEquals为false，或者集合中存在多个元素
                else {
                    // 那么需要遍历集合
                    for (Iterator it = bucket.iterator(); it.hasNext();) {
                        // 获取到对应的string
                        String string = (String)it.next();
                        // 如果next不为null的话，标记next标签
                        if (next != null) {
                            e.mark(next);
                        }
                        // 如果集合还存在元素，那么将栈顶进行switch的元素复制一份再进行比较
                        if (it.hasNext()) {
                            e.dup();
                        }
                        // 将当前遍历到的string压入操作数栈中
                        e.push(string);
                        // 调用equals方法进行比较
                        e.invoke_virtual(Constants.TYPE_OBJECT, EQUALS);
                        // 如果集合还存在元素，那么将栈顶的equals的结果同0进行比较，如果等于0，创建一个label，然后复制给next标签，跳转到next标签；
                        // 如果不等于0，说明equals返回true，直接将栈顶的元素弹出
                        if (it.hasNext()) {
                            e.if_jump(e.EQ, next = e.make_label());
                            e.pop();
                        }
                        // 如果集合中已经不存在元素了，比较结果等于0，跳转到def标签返回默认值；
                        // 如果等于1，执行下面的语句
                        else {
                            e.if_jump(e.EQ, def);
                        }
                        // 调用传入的ObjectSwitchCallback的processCase方法
                        callback.processCase(string, end);
                    }
                }
            }
            public void processDefault() {
                e.pop();
            }
        });
        // 将default标签标记在这里
        e.mark(def);
        callback.processDefault();
        // 将结束标签标记在这里
        e.mark(end);
    }

    public static void load_class_this(CodeEmitter e) {
        load_class_helper(e, e.getClassEmitter().getClassType());
    }
    
    public static void load_class(CodeEmitter e, Type type) {
        if (TypeUtils.isPrimitive(type)) {
            if (type == Type.VOID_TYPE) {
                throw new IllegalArgumentException("cannot load void type");
            }
            e.getstatic(TypeUtils.getBoxedType(type), "TYPE", Constants.TYPE_CLASS);
        } else {
            load_class_helper(e, type);
        }
    }

    private static void load_class_helper(CodeEmitter e, final Type type) {
        // 如果codeEmitter是CGLIB$STATICHOOK方法的
        if (e.isStaticHook()) {
            // have to fall back on non-optimized load
            // 将type转换为className的形式，然后使用ldc加载对应的string常量到操作数栈顶
            e.push(TypeUtils.emulateClassGetName(type));
            // 然后调用Class.forName方法加载类
            e.invoke_static(Constants.TYPE_CLASS, FOR_NAME);
        }
        // 如果codeEmitter不是CGLIB$STATICHOOK方法
        else {
            ClassEmitter ce = e.getClassEmitter();
            String typeName = TypeUtils.emulateClassGetName(type);

            // TODO: can end up with duplicated field names when using chained transformers; incorporate static hook # somehow
            // 生成一个字段名
            String fieldName = "CGLIB$load_class$" + TypeUtils.escapeType(typeName);
            // 判断类中是否声明了该字段
            if (!ce.isFieldDeclared(fieldName)) {
                // 如果没有，声明一个private final static Class类型的字段，名称使用fieldName
                ce.declare_field(Constants.PRIVATE_FINAL_STATIC, fieldName, Constants.TYPE_CLASS, null);
                // 然后获取到CGLIB$STATIHOOK方法的codeEmitter
                CodeEmitter hook = ce.getStaticHook();
                // 向栈顶添加ldc字节码，将类名从常量池压入栈顶
                hook.push(typeName);
                // 调用Class.forName方法
                hook.invoke_static(Constants.TYPE_CLASS, FOR_NAME);
                // 然后将得到的结果通过putstatic放入到刚才声明的字段中
                hook.putstatic(ce.getClassType(), fieldName, Constants.TYPE_CLASS);
            }
            // 然后调用getstatic 获取刚才声明的字段
            e.getfield(fieldName);
        }
    }

    public static void push_array(CodeEmitter e, Object[] array) {
        e.push(array.length);
        e.newarray(Type.getType(remapComponentType(array.getClass().getComponentType())));
        for (int i = 0; i < array.length; i++) {
            e.dup();
            e.push(i);
            push_object(e, array[i]);
            e.aastore();
        }
    }

    private static Class remapComponentType(Class componentType) {
        if (componentType.equals(Type.class))
            return Class.class;
        return componentType;
    }
    
    public static void push_object(CodeEmitter e, Object obj) {
        if (obj == null) {
            e.aconst_null();
        } else {
            Class type = obj.getClass();
            if (type.isArray()) {
                push_array(e, (Object[])obj);
            } else if (obj instanceof String) {
                e.push((String)obj);
            } else if (obj instanceof Type) {
                load_class(e, (Type)obj);
            } else if (obj instanceof Class) {
                load_class(e, Type.getType((Class)obj));
            } else if (obj instanceof BigInteger) {
                e.new_instance(Constants.TYPE_BIG_INTEGER);
                e.dup();
                e.push(obj.toString());
                e.invoke_constructor(Constants.TYPE_BIG_INTEGER);
            } else if (obj instanceof BigDecimal) {
                e.new_instance(Constants.TYPE_BIG_DECIMAL);
                e.dup();
                e.push(obj.toString());
                e.invoke_constructor(Constants.TYPE_BIG_DECIMAL);
            } else {
                throw new IllegalArgumentException("unknown type: " + obj.getClass());
            }
        }
    }

    /**
     * @deprecated use {@link #hash_code(CodeEmitter, Type, int, CustomizerRegistry)} instead
     */
    @Deprecated
    public static void hash_code(CodeEmitter e, Type type, int multiplier, final Customizer customizer) {
    	hash_code(e, type, multiplier, CustomizerRegistry.singleton(customizer));
    }
    
    public static void hash_code(CodeEmitter e, Type type, int multiplier, final CustomizerRegistry registry) {
        // 如果type是数组类型的，调用hash_array方法
        if (TypeUtils.isArray(type)) {
            hash_array(e, type, multiplier, registry);
        }
        // 如果不是
        else {
            // 交换栈顶的两个类型的值
            e.swap(Type.INT_TYPE, type);
            // 然后将multiplier压入栈顶
            e.push(multiplier);
            // 根据type类型获取对应的mul字节码，将栈顶的两个值相乘
            e.math(e.MUL, Type.INT_TYPE);
            // 然后将栈顶的两个值交换
            e.swap(type, Type.INT_TYPE);
            // 如果type是原始类型，调用hash_primitive
            if (TypeUtils.isPrimitive(type)) {
                hash_primitive(e, type);
            }
            // 如果type是引用类型，调用hash_object
            else {
                hash_object(e, type, registry);
            }
            // 然后将栈顶的两个值相加
            e.math(e.ADD, Type.INT_TYPE);
        }
    }

    private static void hash_array(final CodeEmitter e, Type type, final int multiplier, final CustomizerRegistry registry) {
        Label skip = e.make_label();
        Label end = e.make_label();
        e.dup();
        e.ifnull(skip);
        EmitUtils.process_array(e, type, new ProcessArrayCallback() {
            public void processElement(Type type) {
                hash_code(e, type, multiplier, registry);
            }
        });
        e.goTo(end);
        e.mark(skip);
        e.pop();
        e.mark(end);
    }

    private static void hash_object(CodeEmitter e, Type type, CustomizerRegistry registry) {
        // (f == null) ? 0 : f.hashCode();
        Label skip = e.make_label();
        Label end = e.make_label();
        e.dup();
        e.ifnull(skip);
        boolean customHashCode = false;
        for (HashCodeCustomizer customizer : registry.get(HashCodeCustomizer.class)) {
            if (customizer.customize(e, type)) {
                customHashCode = true;
                break;
            }
        }
        if (!customHashCode) {
            for (Customizer customizer : registry.get(Customizer.class)) {
                customizer.customize(e, type);
            }
            e.invoke_virtual(Constants.TYPE_OBJECT, HASH_CODE);
        }
        e.goTo(end);
        e.mark(skip);
        e.pop();
        e.push(0);
        e.mark(end);
    }

    private static void hash_primitive(CodeEmitter e, Type type) {
        switch (type.getSort()) {
        case Type.BOOLEAN:
            // f ? 0 : 1
            e.push(1);
            e.math(e.XOR, Type.INT_TYPE);
            break;
        case Type.FLOAT:
            // Float.floatToIntBits(f)
            e.invoke_static(Constants.TYPE_FLOAT, FLOAT_TO_INT_BITS);
            break;
        case Type.DOUBLE:
            // Double.doubleToLongBits(f), hash_code(Long.TYPE)
            e.invoke_static(Constants.TYPE_DOUBLE, DOUBLE_TO_LONG_BITS);
            // fall through
        case Type.LONG:
            hash_long(e);
        }
    }

    private static void hash_long(CodeEmitter e) {
        // (int)(f ^ (f >>> 32))
        e.dup2();
        e.push(32);
        e.math(e.USHR, Type.LONG_TYPE);
        e.math(e.XOR, Type.LONG_TYPE);
        e.cast_numeric(Type.LONG_TYPE, Type.INT_TYPE);
    }

//     public static void not_equals(CodeEmitter e, Type type, Label notEquals) {
//         not_equals(e, type, notEquals, null);
//     }
    
    /**
     * @deprecated use {@link #not_equals(CodeEmitter, Type, Label, CustomizerRegistry)} instead
     */
    @Deprecated
    public static void not_equals(CodeEmitter e, Type type, final Label notEquals, final Customizer customizer) {
    	not_equals(e, type, notEquals, CustomizerRegistry.singleton(customizer));
    }
    
    /**
     * Branches to the specified label if the top two items on the stack
     * are not equal. The items must both be of the specified
     * class. Equality is determined by comparing primitive values
     * directly and by invoking the <code>equals</code> method for
     * Objects. Arrays are recursively processed in the same manner.
     */
    public static void not_equals(final CodeEmitter e, Type type, final Label notEquals, final CustomizerRegistry registry) {
        (new ProcessArrayCallback() {
            public void processElement(Type type) {
                not_equals_helper(e, type, notEquals, registry, this);
            }
        }).processElement(type);
    }
    
    private static void not_equals_helper(CodeEmitter e,
                                          Type type,
                                          Label notEquals,
                                          CustomizerRegistry registry,
                                          ProcessArrayCallback callback) {
        if (TypeUtils.isPrimitive(type)) {
            e.if_cmp(type, e.NE, notEquals);
        } else {
            Label end = e.make_label();
            nullcmp(e, notEquals, end);
            if (TypeUtils.isArray(type)) {
                Label checkContents = e.make_label();
                e.dup2();
                e.arraylength();
                e.swap();
                e.arraylength();
                e.if_icmp(e.EQ, checkContents);
                e.pop2();
                e.goTo(notEquals);
                e.mark(checkContents);
                EmitUtils.process_arrays(e, type, callback);
            } else {
                List<Customizer> customizers = registry.get(Customizer.class);
                if (!customizers.isEmpty()) {
                    for (Customizer customizer : customizers) {
                        customizer.customize(e, type);
                    }
                    e.swap();
                    for (Customizer customizer : customizers) {
                        customizer.customize(e, type);
                    }
                }
                e.invoke_virtual(Constants.TYPE_OBJECT, EQUALS);
                e.if_jump(e.EQ, notEquals);
            }
            e.mark(end);
        }
    }

    /**
     * If both objects on the top of the stack are non-null, does nothing.
     * If one is null, or both are null, both are popped off and execution
     * branches to the respective label.
     * @param oneNull label to branch to if only one of the objects is null
     * @param bothNull label to branch to if both of the objects are null
     */
    private static void nullcmp(CodeEmitter e, Label oneNull, Label bothNull) {
        e.dup2();
        Label nonNull = e.make_label();
        Label oneNullHelper = e.make_label();
        Label end = e.make_label();
        e.ifnonnull(nonNull);
        e.ifnonnull(oneNullHelper);
        e.pop2();
        e.goTo(bothNull);
        
        e.mark(nonNull);
        e.ifnull(oneNullHelper);
        e.goTo(end);
        
        e.mark(oneNullHelper);
        e.pop2();
        e.goTo(oneNull);
        
        e.mark(end);
    }

    /*
    public static void to_string(CodeEmitter e,
                                 Type type,
                                 ArrayDelimiters delims,
                                 CustomizerRegistry registry) {
        e.new_instance(Constants.TYPE_STRING_BUFFER);
        e.dup();
        e.invoke_constructor(Constants.TYPE_STRING_BUFFER);
        e.swap();
        append_string(e, type, delims, registry);
        e.invoke_virtual(Constants.TYPE_STRING_BUFFER, TO_STRING);
    }
    */

    /**
      * @deprecated use {@link #append_string(CodeEmitter, Type, ArrayDelimiters, CustomizerRegistry)} instead
      */
    @Deprecated
    public static void append_string(final CodeEmitter e,
                                     Type type,
                                     final ArrayDelimiters delims,
                                     final Customizer customizer) {
        append_string(e, type, delims, CustomizerRegistry.singleton(customizer));
    }

    public static void append_string(final CodeEmitter e,
                                     Type type,
                                     final ArrayDelimiters delims,
                                     final CustomizerRegistry registry) {
        final ArrayDelimiters d = (delims != null) ? delims : DEFAULT_DELIMITERS;
        ProcessArrayCallback callback = new ProcessArrayCallback() {
            public void processElement(Type type) {
                append_string_helper(e, type, d, registry, this);
                e.push(d.inside);
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_STRING);
            }
        };
        append_string_helper(e, type, d, registry, callback);
    }

    private static void append_string_helper(CodeEmitter e,
                                             Type type,
                                             ArrayDelimiters delims,
                                             CustomizerRegistry registry,
                                             ProcessArrayCallback callback) {
        Label skip = e.make_label();
        Label end = e.make_label();
        if (TypeUtils.isPrimitive(type)) {
            switch (type.getSort()) {
            case Type.INT:
            case Type.SHORT:
            case Type.BYTE:
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_INT);
                break;
            case Type.DOUBLE:
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_DOUBLE);
                break;
            case Type.FLOAT:
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_FLOAT);
                break;
            case Type.LONG:
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_LONG);
                break;
            case Type.BOOLEAN:
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_BOOLEAN);
                break;
            case Type.CHAR:
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_CHAR);
                break;
            }
        } else if (TypeUtils.isArray(type)) {
            e.dup();
            e.ifnull(skip);
            e.swap();
            if (delims != null && delims.before != null && !"".equals(delims.before)) {
                e.push(delims.before);
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_STRING);
                e.swap();
            }
            EmitUtils.process_array(e, type, callback);
            shrinkStringBuffer(e, 2);
            if (delims != null && delims.after != null && !"".equals(delims.after)) {
                e.push(delims.after);
                e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_STRING);
            }
        } else {
            e.dup();
            e.ifnull(skip);
            for (Customizer customizer : registry.get(Customizer.class)) {
                customizer.customize(e, type);
            }
            e.invoke_virtual(Constants.TYPE_OBJECT, TO_STRING);
            e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_STRING);
        }
        e.goTo(end);
        e.mark(skip);
        e.pop();
        e.push("null");
        e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_STRING);
        e.mark(end);
    }

    private static void shrinkStringBuffer(CodeEmitter e, int amt) {
        e.dup();
        e.dup();
        e.invoke_virtual(Constants.TYPE_STRING_BUFFER, LENGTH);
        e.push(amt);
        e.math(e.SUB, Type.INT_TYPE);
        e.invoke_virtual(Constants.TYPE_STRING_BUFFER, SET_LENGTH);
    }

    public static class ArrayDelimiters {
        private String before;
        private String inside;
        private String after;
            
        public ArrayDelimiters(String before, String inside, String after) {
            this.before = before;
            this.inside = inside;
            this.after = after;
        }
    }

    public static void load_method(CodeEmitter e, MethodInfo method) {
        // 根据methodInfo获取方法的声明类的名称，然后调用Class.forName加载出类对象到操作数栈顶
        load_class(e, method.getClassInfo().getType());
        // 将方法名压入操作数栈顶
        e.push(method.getSignature().getName());
        // 然后将参数类型数组也压入操作数栈顶
        push_object(e, method.getSignature().getArgumentTypes());
        // 然后调用Class的getDeclaredMethod方法，根据方法名和参数类型找到对应的Method对象，放入栈顶
        e.invoke_virtual(Constants.TYPE_CLASS, GET_DECLARED_METHOD);
    }

    private interface ParameterTyper {
        Type[] getParameterTypes(MethodInfo member);
    }

    public static void method_switch(CodeEmitter e,
                                     List methods,
                                     ObjectSwitchCallback callback) {
        member_switch_helper(e, methods, callback, true);
    }

    public static void constructor_switch(CodeEmitter e,
                                          List constructors,
                                          ObjectSwitchCallback callback) {
        member_switch_helper(e, constructors, callback, false);
    }

    private static void member_switch_helper(final CodeEmitter e,
                                             List members,
                                             // 目前只会传入 GetIndexCallback，就是根据MethodInfo获取对应index
                                             final ObjectSwitchCallback callback,
                                             boolean useName) {
        try {
            final Map cache = new HashMap();
            final ParameterTyper cached = new ParameterTyper() {
                    public Type[] getParameterTypes(MethodInfo member) {
                        Type[] types = (Type[])cache.get(member);
                        if (types == null) {
                            cache.put(member, types = member.getSignature().getArgumentTypes());
                        }
                        return types;
                    }
                };
            final Label def = e.make_label();
            final Label end = e.make_label();
            // 如果useName参数为true，通过method_switch调用的时候，该参数为true
            if (useName) {
                // 将栈顶的两个元素交换位置，也就是将方法名和参数类型数组交换。
                // 那么现在栈顶的就是方法名了
                e.swap();
                // 将MethodInfo的集合根据方法名进行分组
                final Map buckets = CollectionUtils.bucket(members, new Transformer() {
                        public Object transform(Object value) {
                            return ((MethodInfo)value).getSignature().getName();
                        }
                    });
                // 然后将MethodInfo里面出现过的方法名转换为数组
                String[] names = (String[])buckets.keySet().toArray(new String[buckets.size()]);
                // 然后根据方法名进行switch匹配
                EmitUtils.string_switch(e, names, Constants.SWITCH_STYLE_HASH, new ObjectSwitchCallback() {
                        public void processCase(Object key, Label dontUseEnd) throws Exception {
                            // 匹配成功之后，获取到buckets中对应的名称相等的那些方法的MethodInfo集合，
                            // 再进行第二个步骤的匹配，即根据参数个数进行匹配
                            member_helper_size(e, (List)buckets.get(key), callback, cached, def, end);
                        }
                        public void processDefault() throws Exception {
                            // 如果没有匹配成功，直接跳转到def标签
                            e.goTo(def);
                        }
                    });
            } else {
                // 如果不使用方法名来匹配的话，直接先按参数个数进行匹配
                member_helper_size(e, members, callback, cached, def, end);
            }
            e.mark(def);
            e.pop();
            // 执行默认的逻辑
            callback.processDefault();
            e.mark(end);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Error ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CodeGenerationException(ex);
        }
    }

    private static void member_helper_size(final CodeEmitter e,
                                           List members,
                                           final ObjectSwitchCallback callback,
                                           final ParameterTyper typer,
                                           final Label def,
                                           final Label end) throws Exception {
        // 将members根据参数个数进行分组
        final Map buckets = CollectionUtils.bucket(members, new Transformer() {
            public Object transform(Object value) {
                return new Integer(typer.getParameterTypes((MethodInfo)value).length);
            }
        });
        // 复制栈顶的参数类型数组
        e.dup();
        // 获取参数类型数组的长度
        e.arraylength();
        // 然后根据参数个数进行switch匹配
        e.process_switch(EmitUtils.getSwitchKeys(buckets), new ProcessSwitchCallback() {
            public void processCase(int key, Label dontUseEnd) throws Exception {
                // 匹配成功之后，获取到那些参数个数匹配的MethodInfo的集合
                List bucket = (List)buckets.get(new Integer(key));
                // 再进行下一个步骤的匹配，根据参数类型进行匹配
                member_helper_type(e, bucket, callback, typer, def, end, new BitSet());
            }
            public void processDefault() throws Exception {
                e.goTo(def);
            }
        });
    }

    private static void member_helper_type(final CodeEmitter e,
                                           List members,
                                           final ObjectSwitchCallback callback,
                                           final ParameterTyper typer,
                                           final Label def,
                                           final Label end,
                                           final BitSet checked) throws Exception {
        // 如果待匹配的MethodInfo集合里面只有一个元素了，直接进行参数类型匹配
        if (members.size() == 1) {
            MethodInfo member = (MethodInfo)members.get(0);
            // 获取到参数类型的Type数组
            Type[] types = typer.getParameterTypes(member);
            // need to check classes that have not already been checked via switches
            // 遍历进行check
            for (int i = 0; i < types.length; i++) {
                // 如果checked位图为null 或者 对应下标的参数类型已经还没有在前置方法里面检查过，那么进行参数类型的检查
                if (checked == null || !checked.get(i)) {
                    // 复制栈顶的参数类型数组
                    e.dup();
                    // 获取对应的位置的参数类型
                    e.aaload(i);
                    // 调用getName方法获取其类型的全限定名
                    e.invoke_virtual(Constants.TYPE_CLASS, GET_NAME);
                    // 获取Type对应的className压入栈顶
                    e.push(TypeUtils.emulateClassGetName(types[i]));
                    // 然后调用equals方法比较栈顶两个元素是否相等
                    e.invoke_virtual(Constants.TYPE_OBJECT, EQUALS);
                    // 如果不相等，跳到def标签
                    e.if_jump(e.EQ, def);
                }
            }
            // 如果匹配结束都相等
            e.pop();
            // 调用最外层的ObjectSwitchCallback的processCase来处理
            callback.processCase(member, end);
        } else {
            // choose the index that has the best chance of uniquely identifying member
            // 获取第一个MethodInfo的参数类型的Type数组
            // 选出参数类型差异最大的那个index
            Type[] example = typer.getParameterTypes((MethodInfo)members.get(0));
            Map buckets = null;
            int index = -1;
            // 遍历参数类型的Type数组
            for (int i = 0; i < example.length; i++) {
                final int j = i;
                // 将MethodInfo集合进行分组
                // key为对应j下标的参数类型的全限定名
                // value为j下标是该参数类型的MethodInfo的集合
                Map test = CollectionUtils.bucket(members, new Transformer() {
                    public Object transform(Object value) {
                        return TypeUtils.emulateClassGetName(typer.getParameterTypes((MethodInfo)value)[j]);
                    }
                });
                // 如果buckets为null  或者 按某一位置的参数类型分组出来的数量 大于 现有的buckets的分组数量
                // 将buckets替换为分组数量更多的map，并且记录参数类型的下标index
                if (buckets == null || test.size() > buckets.size()) {
                    buckets = test;
                    index = i;
                }
            }
            // 如果buckets为null或者分组数量为1，那么说明至少存在两个方法 方法名 方法参数类型都相等，直接跳转到def标签
            if (buckets == null || buckets.size() == 1) {
                // TODO: switch by returnType
                // must have two methods with same name, types, and different return types
                e.goTo(def);
            } else {
                // 否则，将index设置到已检查过的位图中
                checked.set(index);

                // 获取对应index的参数类型
                e.dup();
                e.aaload(index);
                // 调用Class.getName()获取到参数类型的全限定名
                e.invoke_virtual(Constants.TYPE_CLASS, GET_NAME);

                final Map fbuckets = buckets;
                String[] names = (String[])buckets.keySet().toArray(new String[buckets.size()]);
                // 对参数类型的全限定名进行string类型的switch
                EmitUtils.string_switch(e, names, Constants.SWITCH_STYLE_HASH, new ObjectSwitchCallback() {
                    public void processCase(Object key, Label dontUseEnd) throws Exception {
                        // 再次递归进行参数类型的switch操作，直到methodInfo集合的元素为1个时候结束
                        member_helper_type(e, (List)fbuckets.get(key), callback, typer, def, end, checked);
                    }
                    public void processDefault() throws Exception {
                        e.goTo(def);
                    }
                });
            }
        }
    }

    public static void wrap_throwable(Block block, Type wrapper) {
        CodeEmitter e = block.getCodeEmitter();
        // 添加try catch代码块捕获Throwable
        e.catch_exception(block, Constants.TYPE_THROWABLE);
        // new一个Type类型的对象
        e.new_instance(wrapper);
        // 此时栈顶的元素是 Throwable wrapper，执行完dup_x1之后是wrapper Throwable wrapper
        e.dup_x1();
        // 然后交换栈顶的两个元素，变成wrapper wrapper Throwable
        e.swap();
        // 调用wrapper的参数为Throwable的有参构造方法
        e.invoke_constructor(wrapper, CSTRUCT_THROWABLE);
        // 然后通过athrow字节码将栈顶的包装异常抛出
        e.athrow();
    }

    public static void add_properties(ClassEmitter ce, String[] names, Type[] types) {
        for (int i = 0; i < names.length; i++) {
            String fieldName = "$cglib_prop_" + names[i];
            ce.declare_field(Constants.ACC_PRIVATE, fieldName, types[i], null);
            EmitUtils.add_property(ce, names[i], types[i], fieldName);
        }
    }

    public static void add_property(ClassEmitter ce, String name, Type type, String fieldName) {
        String property = TypeUtils.upperFirst(name);
        CodeEmitter e;
        e = ce.begin_method(Constants.ACC_PUBLIC,
                            new Signature("get" + property,
                                          type,
                                          Constants.TYPES_EMPTY),
                            null);
        e.load_this();
        e.getfield(fieldName);
        e.return_value();
        e.end_method();

        e = ce.begin_method(Constants.ACC_PUBLIC,
                            new Signature("set" + property,
                                          Type.VOID_TYPE,
                                          new Type[]{ type }),
                            null);
        e.load_this();
        e.load_arg(0);
        e.putfield(fieldName);
        e.return_value();
        e.end_method();
    }

    /* generates:
       } catch (RuntimeException e) {
         throw e;
       } catch (Error e) {
         throw e;
       } catch (<DeclaredException> e) {
         throw e;
       } catch (Throwable e) {
         throw new <Wrapper>(e);
       }
    */
    public static void wrap_undeclared_throwable(CodeEmitter e, Block handler, Type[] exceptions, Type wrapper) {
        Set set = (exceptions == null) ? Collections.EMPTY_SET : new HashSet(Arrays.asList(exceptions));

        if (set.contains(Constants.TYPE_THROWABLE))
            return;

        boolean needThrow = exceptions != null;
        if (!set.contains(Constants.TYPE_RUNTIME_EXCEPTION)) {
            e.catch_exception(handler, Constants.TYPE_RUNTIME_EXCEPTION);
            needThrow = true;
        }
        if (!set.contains(Constants.TYPE_ERROR)) {
            e.catch_exception(handler, Constants.TYPE_ERROR);
            needThrow = true;
        }
        if (exceptions != null) {
            for (int i = 0; i < exceptions.length; i++) {
                e.catch_exception(handler, exceptions[i]);
            }
        }
        if (needThrow) {
            e.athrow();
        }
        // e -> eo -> oeo -> ooe -> o
        e.catch_exception(handler, Constants.TYPE_THROWABLE);
        e.new_instance(wrapper);
        e.dup_x1();
        e.swap();
        e.invoke_constructor(wrapper, CSTRUCT_THROWABLE);
        e.athrow();
    }

    public static CodeEmitter begin_method(ClassEmitter e, MethodInfo method) {
        return begin_method(e, method, method.getModifiers());
    }

    public static CodeEmitter begin_method(ClassEmitter e, MethodInfo method, int access) {
        return e.begin_method(access,
                              method.getSignature(),
                              method.getExceptionTypes());
    }
}
