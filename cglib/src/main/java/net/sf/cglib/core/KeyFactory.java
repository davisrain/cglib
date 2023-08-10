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

import net.sf.cglib.core.internal.CustomizerRegistry;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.List;

/**
 * Generates classes to handle multi-valued keys, for use in things such as Maps and Sets.
 * Code for <code>equals</code> and <code>hashCode</code> methods follow the
 * the rules laid out in <i>Effective Java</i> by Joshua Bloch. 
 * <p>
 * To generate a <code>KeyFactory</code>, you need to supply an interface which
 * describes the structure of the key. The interface should have a
 * single method named <code>newInstance</code>, which returns an
 * <code>Object</code>. The arguments array can be
 * <i>anything</i>--Objects, primitive values, or single or
 * multi-dimension arrays of either. For example:
 * <p><pre>
 *     private interface IntStringKey {
 *         public Object newInstance(int i, String s);
 *     }
 * </pre><p>
 * Once you have made a <code>KeyFactory</code>, you generate a new key by calling
 * the <code>newInstance</code> method defined by your interface.
 * <p><pre>
 *     IntStringKey factory = (IntStringKey)KeyFactory.create(IntStringKey.class);
 *     Object key1 = factory.newInstance(4, "Hello");
 *     Object key2 = factory.newInstance(4, "World");
 * </pre><p>
 * <b>Note:</b>
 * <code>hashCode</code> equality between two keys <code>key1</code> and <code>key2</code> is only guaranteed if
 * <code>key1.equals(key2)</code> <i>and</i> the keys were produced by the same factory.
 *
 * @version $Id: KeyFactory.java,v 1.26 2006/03/05 02:43:19 herbyderby Exp $
 */
abstract public class KeyFactory {
    private static final Signature GET_NAME =
      TypeUtils.parseSignature("String getName()");
    private static final Signature GET_CLASS =
      TypeUtils.parseSignature("Class getClass()");
    private static final Signature HASH_CODE =
      TypeUtils.parseSignature("int hashCode()");
    private static final Signature EQUALS =
      TypeUtils.parseSignature("boolean equals(Object)");
    private static final Signature TO_STRING =
      TypeUtils.parseSignature("String toString()");
    private static final Signature APPEND_STRING =
      TypeUtils.parseSignature("StringBuffer append(String)");
    private static final Type KEY_FACTORY =
      TypeUtils.parseType("net.sf.cglib.core.KeyFactory");
    private static final Signature GET_SORT =
      TypeUtils.parseSignature("int getSort()");

    //generated numbers: 
    private final static int PRIMES[] = {
               11,         73,        179,       331,
              521,        787,       1213,      1823,
             2609,       3691,       5189,      7247,
            10037,      13931,      19289,     26627,
            36683,      50441,      69403,     95401,
           131129,     180179,     247501,    340057,
           467063,     641371,     880603,   1209107,
          1660097,    2279161,    3129011,   4295723,
          5897291,    8095873,   11114263,  15257791,
         20946017,   28754629,   39474179,  54189869,
         74391461,  102123817,  140194277, 192456917,
        264202273,  362693231,  497900099, 683510293,
        938313161, 1288102441, 1768288259  };
    

    public static final Customizer CLASS_BY_NAME = new Customizer() {
        public void customize(CodeEmitter e, Type type) {
            if (type.equals(Constants.TYPE_CLASS)) {
                e.invoke_virtual(Constants.TYPE_CLASS, GET_NAME);
            }
        }
    };

    public static final FieldTypeCustomizer STORE_CLASS_AS_STRING = new FieldTypeCustomizer() {
        public void customize(CodeEmitter e, int index, Type type) {
            if (type.equals(Constants.TYPE_CLASS)) {
                e.invoke_virtual(Constants.TYPE_CLASS, GET_NAME);
            }
        }

        public Type getOutType(int index, Type type) {
            if (type.equals(Constants.TYPE_CLASS)) {
                return Constants.TYPE_STRING;
            }
            return type;
        }
    };

    /**
     * {@link Type#hashCode()} is very expensive as it traverses full descriptor to calculate hash code.
     * This customizer uses {@link Type#getSort()} as a hash code.
     */
    public static final HashCodeCustomizer HASH_ASM_TYPE = new HashCodeCustomizer() {
        public boolean customize(CodeEmitter e, Type type) {
            if (Constants.TYPE_TYPE.equals(type)) {
                e.invoke_virtual(type, GET_SORT);
                return true;
            }
            return false;
        }
    };

    /**
     * @deprecated this customizer might result in unexpected class leak since key object still holds a strong reference to the Object and class.
     *             It is recommended to have pre-processing method that would strip Objects and represent Classes as Strings
     */
    @Deprecated
    public static final Customizer OBJECT_BY_CLASS = new Customizer() {
        public void customize(CodeEmitter e, Type type) {
            e.invoke_virtual(Constants.TYPE_OBJECT, GET_CLASS);
        }
    };

    protected KeyFactory() {
    }

    public static KeyFactory create(Class keyInterface) {
        return create(keyInterface, null);
    }

    public static KeyFactory create(Class keyInterface, Customizer customizer) {
        return create(keyInterface.getClassLoader(), keyInterface,  customizer);
    }

    public static KeyFactory create(Class keyInterface, KeyFactoryCustomizer first, List<KeyFactoryCustomizer> next) {
        return create(keyInterface.getClassLoader(), keyInterface, first, next);
    }

    public static KeyFactory create(ClassLoader loader, Class keyInterface, Customizer customizer) {
        return create(loader, keyInterface, customizer, Collections.<KeyFactoryCustomizer>emptyList());
    }

    public static KeyFactory create(ClassLoader loader, Class keyInterface, KeyFactoryCustomizer customizer,
                                    List<KeyFactoryCustomizer> next) {
        // 创建一个Generator
        Generator gen = new Generator();
        // 将keyInterface设置进generator中
        gen.setInterface(keyInterface);

        // 如果customizer不为null的话，也添加进generator中去
        if (customizer != null) {
            gen.addCustomizer(customizer);
        }
        // 如果next不为null且不为空的话，遍历里面的KeyFactoryCustomizer全部添加generator中
        if (next != null && !next.isEmpty()) {
            for (KeyFactoryCustomizer keyFactoryCustomizer : next) {
                gen.addCustomizer(keyFactoryCustomizer);
            }
        }
        // 设置generator的classloader为传入的参数loader
        gen.setClassLoader(loader);
        // 然后调用generator的create方法创建一个KeyFactory出来
        return gen.create();
    }

    public static class Generator extends AbstractClassGenerator {
        private static final Source SOURCE = new Source(KeyFactory.class.getName());
        private static final Class[] KNOWN_CUSTOMIZER_TYPES = new Class[]{Customizer.class, FieldTypeCustomizer.class};

        private Class keyInterface;
        // TODO: Make me final when deprecated methods are removed
        // 实例化一个CustomizerRegistry，并且传入Customizer 和 FieldTypeCustomizer作为CustomizerTypes
        private CustomizerRegistry customizers = new CustomizerRegistry(KNOWN_CUSTOMIZER_TYPES);
        private int constant;
        private int multiplier;

        public Generator() {
            super(SOURCE);
        }

        protected ClassLoader getDefaultClassLoader() {
            return keyInterface.getClassLoader();
        }

        protected ProtectionDomain getProtectionDomain() {
        	return ReflectUtils.getProtectionDomain(keyInterface);
        }

        /**
         * @deprecated Use {@link #addCustomizer(KeyFactoryCustomizer)} instead.
         */
        @Deprecated
        public void setCustomizer(Customizer customizer) {
            customizers = CustomizerRegistry.singleton(customizer);
        }
        
        public void addCustomizer(KeyFactoryCustomizer customizer) {
            customizers.add(customizer);
        }

        public <T> List<T> getCustomizers(Class<T> klass) {
            return customizers.get(klass);
        }

        public void setInterface(Class keyInterface) {
            this.keyInterface = keyInterface;
        }

        public KeyFactory create() {
            // 设置namePrefix为keyInterface的全限定名
            setNamePrefix(keyInterface.getName());
            // 调用父类AbstractClassGenerator的create方法，传入keyInterface的全限名作为参数
            return (KeyFactory)super.create(keyInterface.getName());
        }

        public void setHashConstant(int constant) {
            this.constant = constant;
        }

        public void setHashMultiplier(int multiplier) {
            this.multiplier = multiplier;
        }

        protected Object firstInstance(Class type) {
            // 使用默认构造器实例化
            return ReflectUtils.newInstance(type);
        }

        protected Object nextInstance(Object instance) {
            // 直接返回传入的实例对象
            return instance;
        }

        public void generateClass(ClassVisitor v) {
            // 创建一个ClassEmitter将传入的classVisitor包装起来
            ClassEmitter ce = new ClassEmitter(v);

            // 获取keyInterface中声明的newInstance方法，规定该接口只能有这么一个方法，如果有多个方法，在这里会报错；
            // 如果方法名不是newInstance，也会报错
            Method newInstance = ReflectUtils.findNewInstance(keyInterface);
            // 如果newInstance方法的返回值类型不是Object.class，也会报错
            if (!newInstance.getReturnType().equals(Object.class)) {
                throw new IllegalArgumentException("newInstance method must return Object");
            }

            // 获取newInstance方法的参数类型，然后将其转换为asm中的Type类型
            Type[] parameterTypes = TypeUtils.getTypes(newInstance.getParameterTypes());
            // 调用classEmitter的begin_class方法，设置classWriter中的major_version access_flag this_class super_class interfaces等属性
            ce.begin_class(Constants.V1_8, // java版本号
                           Constants.ACC_PUBLIC, // 访问修饰符
                           getClassName(), // 类名
                           KEY_FACTORY, // 父类的描述符
                           new Type[]{ Type.getType(keyInterface) }, // 接口的描述符数组
                           Constants.SOURCE_FILE); // sourceFile
            // 创建一个无参构造器方法
            EmitUtils.null_constructor(ce);
            // 根据newInstance这个方法的反射Method，获取对应的方法签名Signature
            // 然后根据这个Signature和classEmitter，创建一个工厂方法，
            // 该工厂方法的逻辑就等同于调用 自身的有参构造方法 然后返回。
            EmitUtils.factory_method(ce, ReflectUtils.getSignature(newInstance));

            int seed = 0;
            // 向类中添加该类的 有参构造方法
            CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC,
                                            TypeUtils.parseConstructor(parameterTypes),
                                            null);
            // 向method的code添加aload_0字节码
            e.load_this();
            // 向code添加invokespecial superType.<init>:()V字节码
            e.super_invoke_constructor();
            // 向code添加aload_0字节码
            e.load_this();
            // 获取FieldTypeCustomizer类型的customizer集合
            List<FieldTypeCustomizer> fieldTypeCustomizers = getCustomizers(FieldTypeCustomizer.class);
            // 遍历newInstance方法的参数的Type数组
            for (int i = 0; i < parameterTypes.length; i++) {
                // 获取对应的Type
                Type parameterType = parameterTypes[i];
                Type fieldType = parameterType;
                // 遍历fieldTypeCustomizer集合
                for (FieldTypeCustomizer customizer : fieldTypeCustomizers) {
                    // 调用customizer的getOutType方法获取自定义的fieldType
                    fieldType = customizer.getOutType(i, fieldType);
                }
                // 然后计算fieldType的hashcode，添加到seed中
                seed += fieldType.hashCode();
                // 向class中声明一个字段，访问修饰符为private final，name为Field_+i，描述符由type获取
                ce.declare_field(Constants.ACC_PRIVATE | Constants.ACC_FINAL,
                        // 获取fieldName为 FIELD_ + i
                        getFieldName(i),
                        fieldType,
                        null);
                // 向code添加dup字节码，复制栈顶的元素
                e.dup();
                // 向code添加load相关的字节码，将i对应的参数加载到操作数栈顶
                e.load_arg(i);
                // 遍历fieldTypeCustomizers集合
                for (FieldTypeCustomizer customizer : fieldTypeCustomizers) {
                    // 调用fieldTypeCustomizer的customize方法对field进行自定义处理
                    customizer.customize(e, i, parameterType);
                }
                // 向code添加putfield #字段常量索引 的字节码
                e.putfield(getFieldName(i));
            }
            // 根据方法的返回值类型 向code中插入对应类型的return字节码
            e.return_value();
            // 计算方法的max_stack 和 max_locals 以及 stackMapFrame
            e.end_method();
            // 通过以上代码可以得出，该类的有参构造方法就是将传入的参数都赋值给自身对应的属性，其中会包括FieldTypeCustomizer对字段的自定义
            
            // hash code
            // 向类中插入hashcode方法
            e = ce.begin_method(Constants.ACC_PUBLIC, HASH_CODE, null);
            // 如果constant不为0，直接将constant赋值给hc；
            // 否则使用之前计算的seed对PRIMES数组求余，然后获取PRIMES数组对应元素的值
            int hc = (constant != 0) ? constant : PRIMES[(int)(Math.abs(seed) % PRIMES.length)];
            // 如果multiplier不为0，直接将multiplier赋值给hm；
            // 否则使用之前计算的seed乘以13然后对PRIMES数组取余，然后获取PRIMES数组对应元素的值
            int hm = (multiplier != 0) ? multiplier : PRIMES[(int)(Math.abs(seed * 13) % PRIMES.length)];
            // 向code中插入将int类型的值压入栈顶相关的字节码 比如：iconst_m1 iconst_0~5 bipush sipush ldc ldc_w ldc2_w(该字节码是用于long或double的)
            e.push(hc);
            // 遍历newInstance的方法参数
            for (int i = 0; i < parameterTypes.length; i++) {
                // 向code中添加load_0字节码
                e.load_this();
                // 向code中添加获取字段的字节码 比如 getfield getstatic
                e.getfield(getFieldName(i));
                // 该方法内的hashcode的计算逻辑就是hc = hc * hm + hashcode(field), 然后进行下一次循环
                EmitUtils.hash_code(e, parameterTypes[i], hm, customizers);
            }
            // 向code中插入ireturn字节码
            e.return_value();
            // 计算maxStack maxLocals stackMapFrame
            e.end_method();

            // equals
            // 向类中插入equals方法
            e = ce.begin_method(Constants.ACC_PUBLIC, EQUALS, null);
            Label fail = e.make_label();
            e.load_arg(0);
            e.instance_of_this();
            e.if_jump(e.EQ, fail);
            for (int i = 0; i < parameterTypes.length; i++) {
                e.load_this();
                e.getfield(getFieldName(i));
                e.load_arg(0);
                e.checkcast_this();
                e.getfield(getFieldName(i));
                EmitUtils.not_equals(e, parameterTypes[i], fail, customizers);
            }
            e.push(1);
            e.return_value();
            e.mark(fail);
            e.push(0);
            e.return_value();
            e.end_method();

            // toString
            // 向类中插入toString方法
            e = ce.begin_method(Constants.ACC_PUBLIC, TO_STRING, null);
            e.new_instance(Constants.TYPE_STRING_BUFFER);
            e.dup();
            e.invoke_constructor(Constants.TYPE_STRING_BUFFER);
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) {
                    e.push(", ");
                    e.invoke_virtual(Constants.TYPE_STRING_BUFFER, APPEND_STRING);
                }
                e.load_this();
                e.getfield(getFieldName(i));
                EmitUtils.append_string(e, parameterTypes[i], EmitUtils.DEFAULT_DELIMITERS, customizers);
            }
            e.invoke_virtual(Constants.TYPE_STRING_BUFFER, TO_STRING);
            e.return_value();
            e.end_method();

            // 调用classEmitter的end_class方法
            ce.end_class();
        }

        private String getFieldName(int arg) {
            return "FIELD_" + arg;
        }
    }
}
