/*
 * Copyright 2002,2003,2004 The Apache Software Foundation
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

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;

import net.sf.cglib.core.*;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.Label;

/**
 * Generates dynamic subclasses to enable method interception. This
 * class started as a substitute for the standard Dynamic Proxy support
 * included with JDK 1.3, but one that allowed the proxies to extend a
 * concrete base class, in addition to implementing interfaces. The dynamically
 * generated subclasses override the non-final methods of the superclass and
 * have hooks which callback to user-defined interceptor
 * implementations.
 * <p>
 * The original and most general callback type is the {@link MethodInterceptor}, which
 * in AOP terms enables "around advice"--that is, you can invoke custom code both before
 * and after the invocation of the "super" method. In addition you can modify the
 * arguments before calling the super method, or not call it at all.
 * <p>
 * Although <code>MethodInterceptor</code> is generic enough to meet any
 * interception need, it is often overkill. For simplicity and performance, additional
 * specialized callback types, such as {@link LazyLoader} are also available.
 * Often a single callback will be used per enhanced class, but you can control
 * which callback is used on a per-method basis with a {@link CallbackFilter}.
 * <p>
 * The most common uses of this class are embodied in the static helper methods. For
 * advanced needs, such as customizing the <code>ClassLoader</code> to use, you should create
 * a new instance of <code>Enhancer</code>. Other classes within CGLIB follow a similar pattern.
 * <p>
 * All enhanced objects implement the {@link Factory} interface, unless {@link #setUseFactory} is
 * used to explicitly disable this feature. The <code>Factory</code> interface provides an API
 * to change the callbacks of an existing object, as well as a faster and easier way to create
 * new instances of the same type.
 * <p>
 * For an almost drop-in replacement for
 * <code>java.lang.reflect.Proxy</code>, see the {@link Proxy} class.
 */
public class Enhancer extends AbstractClassGenerator
{
    private static final CallbackFilter ALL_ZERO = new CallbackFilter(){
        public int accept(Method method) {
            return 0;
        }
    };

    // source的name为enhancer类的全限定名
    private static final Source SOURCE = new Source(Enhancer.class.getName());
    // 创建了一个实现了EnhancerKey接口 继承了KeyFactory的类，并且实现了接口的newInstance方法，该方法的逻辑就是调用自身的有参构造函数，
    // 将传入的参数都赋值给自身属性持有

    // 该动态生成的类，既是KeyFactory，又是实际的Key，通过create方法创建出来的是所有字段都为null的KeyFactory，
    // 当调用该对象的newInstance方法将参数都传入的时候，会生成一个新的对象，实际就是调用该类的有参构造方法，将参数全部赋值给字段，然后返回。

    // 根据cglib默认的namePolicy，新生成的类的全限定名是:
    // net.sf.cglib.proxy.Enhancer$EnhancerKey$$KeyFactoryByCGLIB$$hashcode("net.sf.cglib.proxy.Enhancer$EnhancerKey")
    // 1.第一个$$前面的内容是AbstractClassGenerator的namePrefix属性
    // 2.第一个$$ 和 第二个$$ 之间的KeyFactory 是AbstractClassGenerator的source.name(net.sf.cglib.core.KeyFactory)最后一个.后面的内容
    // 3.byCGLIB是DefaultNamingPolicy的getTag方法的返回值
    // 4.最后的hashcode，是计算AbstractClassGenerator持有的key属性的hashcode，KeyFactory.Generator的key就是对应的keyInterface的全限定名(net.sf.cglib.proxy.Enhancer$EnhancerKey)
    private static final EnhancerKey KEY_FACTORY =
      (EnhancerKey)KeyFactory.create(EnhancerKey.class, KeyFactory.HASH_ASM_TYPE, null);

    private static final String BOUND_FIELD = "CGLIB$BOUND";
    private static final String FACTORY_DATA_FIELD = "CGLIB$FACTORY_DATA";
    private static final String THREAD_CALLBACKS_FIELD = "CGLIB$THREAD_CALLBACKS";
    private static final String STATIC_CALLBACKS_FIELD = "CGLIB$STATIC_CALLBACKS";
    private static final String SET_THREAD_CALLBACKS_NAME = "CGLIB$SET_THREAD_CALLBACKS";
    private static final String SET_STATIC_CALLBACKS_NAME = "CGLIB$SET_STATIC_CALLBACKS";
    private static final String CONSTRUCTED_FIELD = "CGLIB$CONSTRUCTED";
    /**
     * {@link net.sf.cglib.core.AbstractClassGenerator.ClassLoaderData#generatedClasses} requires to keep cache key
     * in a good shape (the keys should be up and running if the proxy class is alive), and one of the cache keys is
     * {@link CallbackFilter}. That is why the generated class contains static field that keeps strong reference to
     * the {@link #filter}.
     * <p>This dance achieves two goals: ensures generated class is reusable and available through generatedClasses
     * cache, and it enables to unload classloader and the related {@link CallbackFilter} in case user does not need
     * that</p>
     */
    private static final String CALLBACK_FILTER_FIELD = "CGLIB$CALLBACK_FILTER";

    private static final Type OBJECT_TYPE =
      TypeUtils.parseType("Object");
    private static final Type FACTORY =
      TypeUtils.parseType("net.sf.cglib.proxy.Factory");
    private static final Type ILLEGAL_STATE_EXCEPTION =
      TypeUtils.parseType("IllegalStateException");
    private static final Type ILLEGAL_ARGUMENT_EXCEPTION =
      TypeUtils.parseType("IllegalArgumentException");
    private static final Type THREAD_LOCAL =
      TypeUtils.parseType("ThreadLocal");
    private static final Type CALLBACK =
      TypeUtils.parseType("net.sf.cglib.proxy.Callback");
    private static final Type CALLBACK_ARRAY =
      Type.getType(Callback[].class);
    private static final Signature CSTRUCT_NULL =
      TypeUtils.parseConstructor("");
    private static final Signature SET_THREAD_CALLBACKS =
      new Signature(SET_THREAD_CALLBACKS_NAME, Type.VOID_TYPE, new Type[]{ CALLBACK_ARRAY });
    private static final Signature SET_STATIC_CALLBACKS =
      new Signature(SET_STATIC_CALLBACKS_NAME, Type.VOID_TYPE, new Type[]{ CALLBACK_ARRAY });
    private static final Signature NEW_INSTANCE =
      new Signature("newInstance", Constants.TYPE_OBJECT, new Type[]{ CALLBACK_ARRAY });
    private static final Signature MULTIARG_NEW_INSTANCE =
      new Signature("newInstance", Constants.TYPE_OBJECT, new Type[]{
          Constants.TYPE_CLASS_ARRAY,
          Constants.TYPE_OBJECT_ARRAY,
          CALLBACK_ARRAY,
      });
    private static final Signature SINGLE_NEW_INSTANCE =
      new Signature("newInstance", Constants.TYPE_OBJECT, new Type[]{ CALLBACK });
    private static final Signature SET_CALLBACK =
      new Signature("setCallback", Type.VOID_TYPE, new Type[]{ Type.INT_TYPE, CALLBACK });
    private static final Signature GET_CALLBACK =
      new Signature("getCallback", CALLBACK, new Type[]{ Type.INT_TYPE });
    private static final Signature SET_CALLBACKS =
      new Signature("setCallbacks", Type.VOID_TYPE, new Type[]{ CALLBACK_ARRAY });
    private static final Signature GET_CALLBACKS =
      new Signature("getCallbacks", CALLBACK_ARRAY, new Type[0]);
    private static final Signature THREAD_LOCAL_GET =
      TypeUtils.parseSignature("Object get()");
    private static final Signature THREAD_LOCAL_SET =
      TypeUtils.parseSignature("void set(Object)");
    private static final Signature BIND_CALLBACKS =
      TypeUtils.parseSignature("void CGLIB$BIND_CALLBACKS(Object)");

    private EnhancerFactoryData currentData;
    // currentKey表示当前的key对象
    private Object currentKey;

    /** Internal interface, only public due to ClassLoader issues. */
    public interface EnhancerKey {
        public Object newInstance(String type,
                                  String[] interfaces,
                                  WeakCacheKey<CallbackFilter> filter,
                                  Type[] callbackTypes,
                                  boolean useFactory,
                                  boolean interceptDuringConstruction,
                                  Long serialVersionUID);
    }

    // 代理类需要实现的接口数组
    private Class[] interfaces;
    // 代理类的callback选择逻辑
    private CallbackFilter filter;
    // 进行方法代理的callback数组
    private Callback[] callbacks;
    // callback的类型数组
    private Type[] callbackTypes;
    // 表示callbackType是已经被验证过的状态了
    private boolean validateCallbackTypes;
    // 当调用createClass方法的时候会被置为true
    private boolean classOnly;
    // 代理类需要继承的父类
    private Class superclass;
    private Class[] argumentTypes;
    private Object[] arguments;
    // 是否使用Factory的模式，默认为true，
    // 表示代理类会实现Factory接口，通过Factory来创建真实的代理类
    private boolean useFactory = true;
    private Long serialVersionUID;
    private boolean interceptDuringConstruction = true;

    /**
     * Create a new <code>Enhancer</code>. A new <code>Enhancer</code>
     * object should be used for each generated object, and should not
     * be shared across threads. To create additional instances of a
     * generated class, use the <code>Factory</code> interface.
     * @see Factory
     */
    public Enhancer() {
        super(SOURCE);
    }

    /**
     * Set the class which the generated class will extend. As a convenience,
     * if the supplied superclass is actually an interface, <code>setInterfaces</code>
     * will be called with the appropriate argument instead.
     * A non-interface argument must not be declared as final, and must have an
     * accessible constructor.
     * @param superclass class to extend or interface to implement
     * @see #setInterfaces(Class[])
     */
    public void setSuperclass(Class superclass) {
        if (superclass != null && superclass.isInterface()) {
            setInterfaces(new Class[]{ superclass });
        } else if (superclass != null && superclass.equals(Object.class)) {
            // affects choice of ClassLoader
            this.superclass = null;
        } else {
            this.superclass = superclass;
        }
    }

    /**
     * Set the interfaces to implement. The <code>Factory</code> interface will
     * always be implemented regardless of what is specified here.
     * @param interfaces array of interfaces to implement, or null
     * @see Factory
     */
    public void setInterfaces(Class[] interfaces) {
        this.interfaces = interfaces;
    }

    /**
     * Set the {@link CallbackFilter} used to map the generated class' methods
     * to a particular callback index.
     * New object instances will always use the same mapping, but may use different
     * actual callback objects.
     * @param filter the callback filter to use when generating a new class
     * @see #setCallbacks
     */
    public void setCallbackFilter(CallbackFilter filter) {
        this.filter = filter;
    }


    /**
     * Set the single {@link Callback} to use.
     * Ignored if you use {@link #createClass}.
     * @param callback the callback to use for all methods
     * @see #setCallbacks
     */
    public void setCallback(final Callback callback) {
        setCallbacks(new Callback[]{ callback });
    }

    /**
     * Set the array of callbacks to use.
     * Ignored if you use {@link #createClass}.
     * You must use a {@link CallbackFilter} to specify the index into this
     * array for each method in the proxied class.
     * @param callbacks the callback array
     * @see #setCallbackFilter
     * @see #setCallback
     */
    public void setCallbacks(Callback[] callbacks) {
        if (callbacks != null && callbacks.length == 0) {
            throw new IllegalArgumentException("Array cannot be empty");
        }
        this.callbacks = callbacks;
    }

    /**
     * Set whether the enhanced object instances should implement
     * the {@link Factory} interface.
     * This was added for tools that need for proxies to be more
     * indistinguishable from their targets. Also, in some cases it may
     * be necessary to disable the <code>Factory</code> interface to
     * prevent code from changing the underlying callbacks.
     * @param useFactory whether to implement <code>Factory</code>; default is <code>true</code>
     */
    public void setUseFactory(boolean useFactory) {
        this.useFactory = useFactory;
    }

    /**
     * Set whether methods called from within the proxy's constructer
     * will be intercepted. The default value is true. Unintercepted methods
     * will call the method of the proxy's base class, if it exists.
     * @param interceptDuringConstruction whether to intercept methods called from the constructor
     */
    public void setInterceptDuringConstruction(boolean interceptDuringConstruction) {
        this.interceptDuringConstruction = interceptDuringConstruction;
    }

    /**
     * Set the single type of {@link Callback} to use.
     * This may be used instead of {@link #setCallback} when calling
     * {@link #createClass}, since it may not be possible to have
     * an array of actual callback instances.
     * @param callbackType the type of callback to use for all methods
     * @see #setCallbackTypes
     */     
    public void setCallbackType(Class callbackType) {
        setCallbackTypes(new Class[]{ callbackType });
    }
    
    /**
     * Set the array of callback types to use.
     * This may be used instead of {@link #setCallbacks} when calling
     * {@link #createClass}, since it may not be possible to have
     * an array of actual callback instances.
     * You must use a {@link CallbackFilter} to specify the index into this
     * array for each method in the proxied class.
     * @param callbackTypes the array of callback types
     */
    public void setCallbackTypes(Class[] callbackTypes) {
        if (callbackTypes != null && callbackTypes.length == 0) {
            throw new IllegalArgumentException("Array cannot be empty");
        }
        this.callbackTypes = CallbackInfo.determineTypes(callbackTypes);
    }

    /**
     * Generate a new class if necessary and uses the specified
     * callbacks (if any) to create a new object instance.
     * Uses the no-arg constructor of the superclass.
     * @return a new instance
     */
    public Object create() {
        classOnly = false;
        argumentTypes = null;
        return createHelper();
    }

    /**
     * Generate a new class if necessary and uses the specified
     * callbacks (if any) to create a new object instance.
     * Uses the constructor of the superclass matching the <code>argumentTypes</code>
     * parameter, with the given arguments.
     * @param argumentTypes constructor signature
     * @param arguments compatible wrapped arguments to pass to constructor
     * @return a new instance
     */
    public Object create(Class[] argumentTypes, Object[] arguments) {
        classOnly = false;
        if (argumentTypes == null || arguments == null || argumentTypes.length != arguments.length) {
            throw new IllegalArgumentException("Arguments must be non-null and of equal length");
        }
        this.argumentTypes = argumentTypes;
        this.arguments = arguments;
        return createHelper();
    }

    /**
     * Generate a new class if necessary and return it without creating a new instance.
     * This ignores any callbacks that have been set.
     * To create a new instance you will have to use reflection, and methods
     * called during the constructor will not be intercepted. To avoid this problem,
     * use the multi-arg <code>create</code> method.
     * @see #create(Class[], Object[])
     */
    public Class createClass() {
        classOnly = true;
        return (Class)createHelper();
    }

    /**
     * Insert a static serialVersionUID field into the generated class.
     * @param sUID the field value, or null to avoid generating field.
     */
    public void setSerialVersionUID(Long sUID) {
        serialVersionUID = sUID;
    }

    private void preValidate() {
        // 如果callbackTypes为null的话，根据callbacks推断出类型
        if (callbackTypes == null) {
            callbackTypes = CallbackInfo.determineTypes(callbacks, false);
            // 并且将validateCallbackTypes设置为true
            validateCallbackTypes = true;
        }
        // 如果CallbackFilter为null的话
        if (filter == null) {
            // 判断callbackTypes的长度是否大于1，如果是，报错
            if (callbackTypes.length > 1) {
                throw new IllegalStateException("Multiple callback types possible but no filter specified");
            }
            // 否则，使用ALL_ZERO这个filter，所有方法都选择下标为0的callback
            filter = ALL_ZERO;
        }
    }

    private void validate() {
        // 如果classOnly为true 但是callback不为null
        // 或者classOnly为false 但是callback为null
        // 报错
        if (classOnly ^ (callbacks == null)) {
            if (classOnly) {
                throw new IllegalStateException("createClass does not accept callbacks");
            } else {
                throw new IllegalStateException("Callbacks are required");
            }
        }
        // 如果classOnly为true 并且 callbackTypes为null 报错
        if (classOnly && (callbackTypes == null)) {
            throw new IllegalStateException("Callback types are required");
        }
        // 如果validateCallbackTypes为true，将callbackTypes置为null，重新验证
        if (validateCallbackTypes) {
            callbackTypes = null;
        }
        // 如果callback和callbackType同时存在，检验数量和类型是否匹配
        if (callbacks != null && callbackTypes != null) {
            if (callbacks.length != callbackTypes.length) {
                throw new IllegalStateException("Lengths of callback and callback types array must be the same");
            }
            Type[] check = CallbackInfo.determineTypes(callbacks);
            for (int i = 0; i < check.length; i++) {
                if (!check[i].equals(callbackTypes[i])) {
                    throw new IllegalStateException("Callback " + check[i] + " is not assignable to " + callbackTypes[i]);
                }
            }
        }
        // 如果只有callback存在，根据callback获取callbackType
        else if (callbacks != null) {
            callbackTypes = CallbackInfo.determineTypes(callbacks);
        }
        // 如果interfaces不为null，检查interface数组里面的类是否都是接口
        if (interfaces != null) {
            for (int i = 0; i < interfaces.length; i++) {
                if (interfaces[i] == null) {
                    throw new IllegalStateException("Interfaces cannot be null");
                }
                if (!interfaces[i].isInterface()) {
                    throw new IllegalStateException(interfaces[i] + " is not an interface");
                }
            }
        }
    }

    /**
     * The idea of the class is to cache relevant java.lang.reflect instances so
     * proxy-class can be instantiated faster that when using {@link ReflectUtils#newInstance(Class, Class[], Object[])}
     * and {@link Enhancer#setThreadCallbacks(Class, Callback[])}
     */
    static class EnhancerFactoryData {
        public final Class generatedClass;
        private final Method setThreadCallbacks;
        private final Class[] primaryConstructorArgTypes;
        private final Constructor primaryConstructor;

        public EnhancerFactoryData(Class generatedClass, Class[] primaryConstructorArgTypes, boolean classOnly) {
            // 将生成的class对象赋值给自身字段
            this.generatedClass = generatedClass;
            try {
                // 然后从类中查找CGLIB$SET_THREAD_CALLBACKS这个静态方法的反射对象
                setThreadCallbacks = getCallbacksSetter(generatedClass, SET_THREAD_CALLBACKS_NAME);
                // 如果classOnly为true的话，主要的构造器参数类型和主要的构造器都为null
                if (classOnly) {
                    this.primaryConstructorArgTypes = null;
                    this.primaryConstructor = null;
                }
                // 如果为false的话，那么将主要构造器的参数类型赋值给自身属性，然后根据参数类型从类中查找到对应的构造器
                else {
                    this.primaryConstructorArgTypes = primaryConstructorArgTypes;
                    this.primaryConstructor = ReflectUtils.getConstructor(generatedClass, primaryConstructorArgTypes);
                }
            } catch (NoSuchMethodException e) {
                throw new CodeGenerationException(e);
            }
        }

        /**
         * Creates proxy instance for given argument types, and assigns the callbacks.
         * Ideally, for each proxy class, just one set of argument types should be used,
         * otherwise it would have to spend time on constructor lookup.
         * Technically, it is a re-implementation of {@link Enhancer#createUsingReflection(Class)},
         * with "cache {@link #setThreadCallbacks} and {@link #primaryConstructor}"
         *
         * @see #createUsingReflection(Class)
         * @param argumentTypes constructor argument types
         * @param arguments constructor arguments
         * @param callbacks callbacks to set for the new instance
         * @return newly created proxy
         */
        public Object newInstance(Class[] argumentTypes, Object[] arguments, Callback[] callbacks) {
            // 通过反射调用类中声明的静态方法CGLIB$SET_THREAD_CALLBACKS，将callback数组设置进ThreadLocal中
            setThreadCallbacks(callbacks);
            try {
                // Explicit reference equality is added here just in case Arrays.equals does not have one
                // 如果传入的参数类型 和 data持有的主要构造器的参数类型相等 或者 equals
                if (primaryConstructorArgTypes == argumentTypes ||
                        Arrays.equals(primaryConstructorArgTypes, argumentTypes)) {
                    // If we have relevant Constructor instance at hand, just call it
                    // This skips "get constructors" machinery
                    // 那么直接使用主要构造器进行实例化
                    return ReflectUtils.newInstance(primaryConstructor, arguments);
                }
                // Take a slow path if observing unexpected argument types
                // 否则根据参数类型从类中选择对应的构造器进行初始化
                return ReflectUtils.newInstance(generatedClass, argumentTypes, arguments);
            } finally {
                // clear thread callbacks to allow them to be gc'd
                // 将ThreadLocal中的元素置为null
                setThreadCallbacks(null);
            }

        }

        private void setThreadCallbacks(Callback[] callbacks) {
            try {
                setThreadCallbacks.invoke(generatedClass, (Object) callbacks);
            } catch (IllegalAccessException e) {
                throw new CodeGenerationException(e);
            } catch (InvocationTargetException e) {
                throw new CodeGenerationException(e.getTargetException());
            }
        }
    }

    private Object createHelper() {
        // 进行预检验。
        // 1.检查callbackTypes是否有值，如果没有，根据callbacks进行推断
        // 2.检查CallbackFilter是否存在，如果不存在且callback数量大于1，报错；如果不存在且数量不大于1的话，使用ALL_ZERO这个filter
        preValidate();
        // 调用代理生成的KEY_FACTORY对象的newInstance方法，生成一个EnhancerKey类型的对象
        // 并且将enhancer持有的属性传入
        Object key = KEY_FACTORY.newInstance((superclass != null) ? superclass.getName() : null,
                ReflectUtils.getNames(interfaces),
                filter == ALL_ZERO ? null : new WeakCacheKey<CallbackFilter>(filter),
                callbackTypes,
                useFactory,
                interceptDuringConstruction,
                serialVersionUID);
        // 将其赋值给currentKey属性
        this.currentKey = key;
        // 调用父类的create方法，并且将key传入
        // 作为AbstractClassGenerator的key
        Object result = super.create(key);
        return result;
    }

    @Override
    protected Class generate(ClassLoaderData data) {
        // 对enhancer持有的属性进行验证操作
        validate();
        // 如果superClass不为null，设置namePrefix为superClass的全限定名
        if (superclass != null) {
            setNamePrefix(superclass.getName());
        }
        // 如果superClass为null，interfaces不为null的话，找到不是被public修饰的接口，然后将其全限定名作为namePrefix
        // 如果都为public，选择第一个接口的name作为namePrefix
        else if (interfaces != null) {
            setNamePrefix(interfaces[ReflectUtils.findPackageProtected(interfaces)].getName());
        }
        // 调用父类的generate方法
        return super.generate(data);
    }

    protected ClassLoader getDefaultClassLoader() {
        if (superclass != null) {
            return superclass.getClassLoader();
        } else if (interfaces != null) {
            return interfaces[0].getClassLoader();
        } else {
            return null;
        }
    }

    protected ProtectionDomain getProtectionDomain() {
        if (superclass != null) {
        	return ReflectUtils.getProtectionDomain(superclass);
        } else if (interfaces != null) {
        	return ReflectUtils.getProtectionDomain(interfaces[0]);
        } else {
            return null;
        }
    }

    private Signature rename(Signature sig, int index) {
        return new Signature("CGLIB$" + sig.getName() + "$" + index,
                             sig.getDescriptor());
    }
    
    /**
     * Finds all of the methods that will be extended by an
     * Enhancer-generated class using the specified superclass and
     * interfaces. This can be useful in building a list of Callback
     * objects. The methods are added to the end of the given list.  Due
     * to the subclassing nature of the classes generated by Enhancer,
     * the methods are guaranteed to be non-static, non-final, and
     * non-private. Each method signature will only occur once, even if
     * it occurs in multiple classes.
     * @param superclass the class that will be extended, or null
     * @param interfaces the list of interfaces that will be implemented, or null
     * @param methods the list into which to copy the applicable methods
     */
    public static void getMethods(Class superclass, Class[] interfaces, List methods)
    {
        getMethods(superclass, interfaces, methods, null, null);
    }

    private static void getMethods(Class superclass, Class[] interfaces, List methods, List interfaceMethods, Set forcePublic)
    {
        // 获取superclass和其父类以及实现的接口中声明的方法，添加到methods集合中
        ReflectUtils.addAllMethods(superclass, methods);
        // 如果用于保存接口方法的集合不为空，那么使用interfaceMethods，否则使用methods作为容器
        List target = (interfaceMethods != null) ? interfaceMethods : methods;
        // 如果interfaces集合不为null
        if (interfaces != null) {
            // 遍历interface
            for (int i = 0; i < interfaces.length; i++) {
                // 如果interface不为Factory.class，那么将其自身和实现的接口中声明的方法都添加进target集合中
                if (interfaces[i] != Factory.class) {
                    ReflectUtils.addAllMethods(interfaces[i], target);
                }
            }
        }
        // 如果interfaceMethods不为null
        if (interfaceMethods != null) {
            // 并且forcePublic也不为null
            if (forcePublic != null) {
                // 将interfaceMethods里面的元素全部通过MethodWrapper包装起来，包装为MethodWrapperKey的set集合添加进forcePublic中
                forcePublic.addAll(MethodWrapper.createSet(interfaceMethods));
            }
            // 将interfaceMethods集合里的元素添加到methods集合中
            methods.addAll(interfaceMethods);
        }
        // 对methods里面的方法进行过滤
        // 首先对访问修饰符进行过滤，如果方法是static的，过滤掉
        CollectionUtils.filter(methods, new RejectModifierPredicate(Constants.ACC_STATIC));
        // 然后对可见性进行过滤，将private的方法过滤掉，并且如果是package级别的方法，判断其声明类的包名，如果和superclass的包名不一致，也过滤掉
        CollectionUtils.filter(methods, new VisibilityPredicate(superclass, true));
        // 将签名重复的方法过滤掉，由于遍历方法时的顺序，所以越早出现的越不容易被过滤掉，并且该predicate会拒绝掉修改访问范围的桥接方法
        CollectionUtils.filter(methods, new DuplicatesPredicate(methods));
        // 将访问修饰符存在final的方法过滤掉
        CollectionUtils.filter(methods, new RejectModifierPredicate(Constants.ACC_FINAL));
    }

    public void generateClass(ClassVisitor v) throws Exception {
        // 如果superclass为null的话，使用Object.class，否则使用superclass
        Class sc = (superclass == null) ? Object.class : superclass;

        // 判断superclass是否是被final修饰的，如果是，报错
        if (TypeUtils.isFinal(sc.getModifiers()))
            throw new IllegalArgumentException("Cannot subclass final class " + sc.getName());
        // 获取superclass声明的构造器
        List constructors = new ArrayList(Arrays.asList(sc.getDeclaredConstructors()));
        // 过滤声明的构造器，默认过滤掉所有private修饰的构造器
        filterConstructors(sc, constructors);

        // Order is very important: must add superclass, then
        // its superclass chain, then each interface and
        // its superinterfaces.
        // 遍历顺序很重要，必须先添加superclass的方法，然后是superclass chain的方法，然后是interface的方法，再然后是super interfaces的方法
        // 创建三个集合来保存不同类型的方法

        // <Method>
        List actualMethods = new ArrayList();
        // <Method>
        List interfaceMethods = new ArrayList();
        // <MethodWrapperKey>
        final Set forcePublic = new HashSet();
        // 从sc和interfaces中获取方法
        getMethods(sc, interfaces, actualMethods, interfaceMethods, forcePublic);

        // 遍历actualMethods集合，进行转换.
        // <MethodInfo>
        List methods = CollectionUtils.transform(actualMethods, new Transformer() {
            public Object transform(Object value) {
                Method method = (Method)value;
                // 获取method的访问修饰符，然后将abstract native synchronized标志去掉，再添加上final标志
                int modifiers = Constants.ACC_FINAL
                    | (method.getModifiers()
                       & ~Constants.ACC_ABSTRACT
                       & ~Constants.ACC_NATIVE
                       & ~Constants.ACC_SYNCHRONIZED);
                // 如果forcePublic里面包含了method对应的MethodWrapperKey
                if (forcePublic.contains(MethodWrapper.create(method))) {
                    // 那么将访问修饰符去掉protected 添加上public标志
                    modifiers = (modifiers & ~Constants.ACC_PROTECTED) | Constants.ACC_PUBLIC;
                }
                // 将method转换为MethodInfo返回，并且放入到集合中，且访问修饰符使用新生成的
                return ReflectUtils.getMethodInfo(method, modifiers);
            }
        });

        // 将classVisitor包装成一个ClassEmitter，这里传入的classVisitor是DebuggingClassWriter
        ClassEmitter e = new ClassEmitter(v);
        // 如果currentData为null
        if (currentData == null) {
            // 调用ce的begin_class，添加class文件的version access_flags this_class super_class interfaces
        e.begin_class(Constants.V1_8,
                      Constants.ACC_PUBLIC,
                      getClassName(),
                      Type.getType(sc),
                      // 根据useFactory来决定是否要添加Factory接口，默认是添加的
                      (useFactory ?
                       TypeUtils.add(TypeUtils.getTypes(interfaces), FACTORY) :
                       TypeUtils.getTypes(interfaces)),
                      Constants.SOURCE_FILE);
        }
        // 如果currentData不为null的话
        else {
            // 调用ce的begin_class，但是不传入superType，且接口只有Factory
            e.begin_class(Constants.V1_8,
                    Constants.ACC_PUBLIC,
                    getClassName(),
                    null,
                    new Type[]{FACTORY},
                    Constants.SOURCE_FILE);
        }
        // 将构造器集合也转换为MethodInfo对象的集合
        // <MethodInfo>
        List constructorInfo = CollectionUtils.transform(constructors, MethodInfoTransformer.getInstance());

        // 声明一个private的名为CGLIB$BOUND的boolean类型的属性，并且不存在ConstantValue
        // public boolean CGLIB$BOUND;
        e.declare_field(Constants.ACC_PRIVATE, BOUND_FIELD, Type.BOOLEAN_TYPE, null);
        // 声明一个public static的名为CGLIB$FACTORY_DATA的Object类型的属性，不存在ConstantValue
        // public static Object CGLIB$FACTORY_DATA;
        e.declare_field(Constants.ACC_PUBLIC | Constants.ACC_STATIC, FACTORY_DATA_FIELD, OBJECT_TYPE, null);
        // 如果interceptDuringConstruction为false
        if (!interceptDuringConstruction) {
            // 声明一个private的名为CGLIB$CONSTRUCTED的boolean类型的属性，不存在ConstantValue
            // private boolean CGLIB$CONSTRUCTED;
            e.declare_field(Constants.ACC_PRIVATE, CONSTRUCTED_FIELD, Type.BOOLEAN_TYPE, null);
        }
        // 声明一个private static final的名为CGLIB$THREAD_CALLBACKS的ThreadLocal类型的属性
        // private static final ThreadLocal CGLIB$THREAD_CALLBACKS;
        e.declare_field(Constants.PRIVATE_FINAL_STATIC, THREAD_CALLBACKS_FIELD, THREAD_LOCAL, null);
        // 声明一个private static final的名为CGLIB$STATIC_CALLBACKS的CALLBACK[]类型的属性
        // private static final Callback[] CGLIB$STATIC_CALLBACKS;
        e.declare_field(Constants.PRIVATE_FINAL_STATIC, STATIC_CALLBACKS_FIELD, CALLBACK_ARRAY, null);
        // 如果serialVersionUID不为null
        if (serialVersionUID != null) {
            // 声明一个private static final的名为serialVersionUID的long类型的属性，其中ConstantValue等于serialVersionUID变量的值
            // private static final long serialVersionUID = serialVersionUID;
            e.declare_field(Constants.PRIVATE_FINAL_STATIC, Constants.SUID_FIELD_NAME, Type.LONG_TYPE, serialVersionUID);
        }

        // 遍历callbackTypes数组
        for (int i = 0; i < callbackTypes.length; i++) {
            // 依次声明private的名为CGLIB$CALLBACK_i的callbackType类型的属性
            // private {CallbackType} CGLIB$CALLBACK_i;
            e.declare_field(Constants.ACC_PRIVATE, getCallbackField(i), callbackTypes[i], null);
        }
        // This is declared private to avoid "public field" pollution
        // private static Object CGLIB$CALLBACK_FILTER;
        e.declare_field(Constants.ACC_PRIVATE | Constants.ACC_STATIC, CALLBACK_FILTER_FIELD, OBJECT_TYPE, null);

        // 如果currentData为null
        if (currentData == null) {
            // 调用emitMethods声明actualMethods集合中的方法
            emitMethods(e, methods, actualMethods);
            // 调用emitConstructors根据constructorInfo集合声明构造器
            emitConstructors(e, constructorInfo);
        }
        // 如果currentData不为null
        else {
            // 声明默认的构造器
            emitDefaultConstructor(e);
        }
        // 在类中声明public static void CGLIB$SET_THREAD_CALLBACKS(Callback[] callbacks)方法
        emitSetThreadCallbacks(e);
        // 在类中声明public static void CGLIB$SET_STATIC_CALLBACKS(Callback[] callbacks)方法
        emitSetStaticCallbacks(e);
        // 在类中声明private static final void CGLIB$BIND_CALLBACKS(Object proxy)方法
        emitBindCallbacks(e);

        // 如果useFactory标志为true(默认是true)  或者 currentData不为null，需要在类中声明下面的方法
        if (useFactory || currentData != null) {
            // 获取一个int数组，长度和callbackTypes数组相同，且每个元素就是自身所在的index
            int[] keys = getCallbackKeys();
            // 声明一个public Object newInstance(Callback[] callbacks)方法
            emitNewInstanceCallbacks(e);
            // 声明一个public Object newInstance(Callback callback)方法
            emitNewInstanceCallback(e);
            // 声明一个public Object newInstance(Class[] classes, Object[] objects, Callback[] callbacks)方法
            emitNewInstanceMultiarg(e, constructorInfo);
            // 声明一个public Callback getCallback(int index)方法
            emitGetCallback(e, keys);
            // 声明一个public void setCallback(int index, Callback callback)方法
            emitSetCallback(e, keys);
            // 声明一个public Callback[] getCallbacks()方法
            emitGetCallbacks(e);
            // 声明一个public void setCallbacks(Callback[] callbacks)方法
            emitSetCallbacks(e);
        }

        e.end_class();
    }

    /**
     * Filter the list of constructors from the superclass. The
     * constructors which remain will be included in the generated
     * class. The default implementation is to filter out all private
     * constructors, but subclasses may extend Enhancer to override this
     * behavior.
     * @param sc the superclass
     * @param constructors the list of all declared constructors from the superclass
     * @throws IllegalArgumentException if there are no non-private constructors
     */
    protected void filterConstructors(Class sc, List constructors) {
        // 对构造器进行可见性判断，默认是过滤掉所有private修饰的构造器
        CollectionUtils.filter(constructors, new VisibilityPredicate(sc, true));
        if (constructors.size() == 0)
            throw new IllegalArgumentException("No visible constructors in " + sc);
    }

    /**
     * This method should not be called in regular flow.
     * Technically speaking {@link #wrapCachedClass(Class)} uses {@link EnhancerFactoryData} as a cache value,
     * and the latter enables faster instantiation than plain old reflection lookup and invoke.
     * This method is left intact for backward compatibility reasons: just in case it was ever used.
     *
     * @param type class to instantiate
     * @return newly created proxy instance
     * @throws Exception if something goes wrong
     */
    protected Object firstInstance(Class type) throws Exception {
        // 如果classOnly为true的话，直接返回class对象
        if (classOnly) {
            return type;
        }
        // 如果为false，调用createUsingReflection，即通过反射创建实例返回
        else {
            return createUsingReflection(type);
        }
    }

    protected Object nextInstance(Object instance) {
        // 将instance强转为EnhancerFactoryData类型的
        EnhancerFactoryData data = (EnhancerFactoryData) instance;

        // 如果classOnly是true的话，直接返回data中的generatedClass字段
        if (classOnly) {
            return data.generatedClass;
        }

        // 否则，获取自身持有的参数类型和参数
        Class[] argumentTypes = this.argumentTypes;
        Object[] arguments = this.arguments;
        if (argumentTypes == null) {
            argumentTypes = Constants.EMPTY_CLASS_ARRAY;
            arguments = null;
        }
        // 然后调用data的newInstance方法，并且传入参数类型 参数 和callbacks数组
        return data.newInstance(argumentTypes, arguments, callbacks);
    }

    @Override
    protected Object wrapCachedClass(Class klass) {
        // 获取自身持有的参数类型
        Class[] argumentTypes = this.argumentTypes;
        // 如果参数类型为null的话，用空数组复制给它
        if (argumentTypes == null) {
            argumentTypes = Constants.EMPTY_CLASS_ARRAY;
        }
        // 根据刚才生成的class对象 参数类型数组 以及classOnly字段实例化一个EnhancerFactoryData
        EnhancerFactoryData factoryData = new EnhancerFactoryData(klass, argumentTypes, classOnly);
        Field factoryDataField = null;
        try {
            // The subsequent dance is performed just once for each class,
            // so it does not matter much how fast it goes
            // 从类中获取CGLIB$FACTORY_DATA这个静态变量
            factoryDataField = klass.getField(FACTORY_DATA_FIELD);
            // 然后将刚才创建的EnhancerFactoryData设置进去
            factoryDataField.set(null, factoryData);
            // 从类中获取CGLIB$CALLBACK_FILTER这个静态字段
            Field callbackFilterField = klass.getDeclaredField(CALLBACK_FILTER_FIELD);
            callbackFilterField.setAccessible(true);
            // 然后将自身持有的filter设置进去
            callbackFilterField.set(null, this.filter);
        } catch (NoSuchFieldException e) {
            throw new CodeGenerationException(e);
        } catch (IllegalAccessException e) {
            throw new CodeGenerationException(e);
        }
        // 最后使用一个弱引用包裹住factoryData并返回
        return new WeakReference<EnhancerFactoryData>(factoryData);
    }

    @Override
    protected Object unwrapCachedValue(Object cached) {
        // 如果currentKey是EnhancerKey类型的，获取弱引用中持有的EnhancerFactoryData对象并返回
        if (currentKey instanceof EnhancerKey) {
            EnhancerFactoryData data = ((WeakReference<EnhancerFactoryData>) cached).get();
            return data;
        }
        return super.unwrapCachedValue(cached);
    }

    /**
     * Call this method to register the {@link Callback} array to use before
     * creating a new instance of the generated class via reflection. If you are using
     * an instance of <code>Enhancer</code> or the {@link Factory} interface to create
     * new instances, this method is unnecessary. Its primary use is for when you want to
     * cache and reuse a generated class yourself, and the generated class does
     * <i>not</i> implement the {@link Factory} interface.
     * <p>
     * Note that this method only registers the callbacks on the current thread.
     * If you want to register callbacks for instances created by multiple threads,
     * use {@link #registerStaticCallbacks}.
     * <p>
     * The registered callbacks are overwritten and subsequently cleared
     * when calling any of the <code>create</code> methods (such as
     * {@link #create}), or any {@link Factory} <code>newInstance</code> method.
     * Otherwise they are <i>not</i> cleared, and you should be careful to set them
     * back to <code>null</code> after creating new instances via reflection if
     * memory leakage is a concern.
     * @param generatedClass a class previously created by {@link Enhancer}
     * @param callbacks the array of callbacks to use when instances of the generated
     * class are created
     * @see #setUseFactory
     */
    public static void registerCallbacks(Class generatedClass, Callback[] callbacks) {
        setThreadCallbacks(generatedClass, callbacks);
    }

    /**
     * Similar to {@link #registerCallbacks}, but suitable for use
     * when multiple threads will be creating instances of the generated class.
     * The thread-level callbacks will always override the static callbacks.
     * Static callbacks are never cleared.
     * @param generatedClass a class previously created by {@link Enhancer}
     * @param callbacks the array of callbacks to use when instances of the generated
     * class are created
     */
    public static void registerStaticCallbacks(Class generatedClass, Callback[] callbacks) {
        setCallbacksHelper(generatedClass, callbacks, SET_STATIC_CALLBACKS_NAME);
    }

    /**
     * Determine if a class was generated using <code>Enhancer</code>.
     * @param type any class
     * @return whether the class was generated  using <code>Enhancer</code>
     */
    public static boolean isEnhanced(Class type) {
        try {
            getCallbacksSetter(type, SET_THREAD_CALLBACKS_NAME);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void setThreadCallbacks(Class type, Callback[] callbacks) {
        setCallbacksHelper(type, callbacks, SET_THREAD_CALLBACKS_NAME);
    }

    private static void setCallbacksHelper(Class type, Callback[] callbacks, String methodName) {
        // TODO: optimize
        try {
            // 获取CGLIB$SET_THREAD_CALLBACKS这个静态方法
            Method setter = getCallbacksSetter(type, methodName);
            // 然后反射调用
            setter.invoke(null, new Object[]{ callbacks });
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(type + " is not an enhanced class");
        } catch (IllegalAccessException e) {
            throw new CodeGenerationException(e);
        } catch (InvocationTargetException e) {
            throw new CodeGenerationException(e);
        }
    }

    private static Method getCallbacksSetter(Class type, String methodName) throws NoSuchMethodException {
        return type.getDeclaredMethod(methodName, new Class[]{ Callback[].class });
    }

    /**
     * Instantiates a proxy instance and assigns callback values.
     * Implementation detail: java.lang.reflect instances are not cached, so this method should not
     * be used on a hot path.
     * This method is used when {@link #setUseCache(boolean)} is set to {@code false}.
     *
     * @param type class to instantiate
     * @return newly created instance
     */
    private Object createUsingReflection(Class type) {
        // 将callbacks设置进类的CGLIB$THREAD_CALLBACKS这个ThreadLocal类型的静态变量中
        setThreadCallbacks(type, callbacks);
        try{
            // 如果参数类型不为null的话，将参数类型和参数一起传入，调用ReflectUtils的newInstance方法实例化一个对象返回
        if (argumentTypes != null) {
        	
             return ReflectUtils.newInstance(type, argumentTypes, arguments);
             
        }
        // 否则的话，使用无参构造器反射实例化
        else {
        	
            return ReflectUtils.newInstance(type);
            
        }
        }finally{
         // clear thread callbacks to allow them to be gc'd
            // 然后再次调用CGLIB$SET_THREAD_CALLBACKS这个静态方法将ThreadLocal中的元素设置为null
         setThreadCallbacks(type, null);
        }
    }

    /**
     * Helper method to create an intercepted object.
     * For finer control over the generated instance, use a new instance of <code>Enhancer</code>
     * instead of this static method.
     * @param type class to extend or interface to implement
     * @param callback the callback to use for all methods
     */
    public static Object create(Class type, Callback callback) {
        Enhancer e = new Enhancer();
        e.setSuperclass(type);
        e.setCallback(callback);
        return e.create();
    }

    /**
     * Helper method to create an intercepted object.
     * For finer control over the generated instance, use a new instance of <code>Enhancer</code>
     * instead of this static method.
     * @param superclass class to extend or interface to implement
     * @param interfaces array of interfaces to implement, or null
     * @param callback the callback to use for all methods
     */
    public static Object create(Class superclass, Class interfaces[], Callback callback) {
        Enhancer e = new Enhancer();
        e.setSuperclass(superclass);
        e.setInterfaces(interfaces);
        e.setCallback(callback);
        return e.create();
    }

    /**
     * Helper method to create an intercepted object.
     * For finer control over the generated instance, use a new instance of <code>Enhancer</code>
     * instead of this static method.
     * @param superclass class to extend or interface to implement
     * @param interfaces array of interfaces to implement, or null
     * @param filter the callback filter to use when generating a new class
     * @param callbacks callback implementations to use for the enhanced object
     */
    public static Object create(Class superclass, Class[] interfaces, CallbackFilter filter, Callback[] callbacks) {
        Enhancer e = new Enhancer();
        e.setSuperclass(superclass);
        e.setInterfaces(interfaces);
        e.setCallbackFilter(filter);
        e.setCallbacks(callbacks);
        return e.create();
    }

    private void emitDefaultConstructor(ClassEmitter ce) {
        Constructor<Object> declaredConstructor;
        try {
            declaredConstructor = Object.class.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Object should have default constructor ", e);
        }
        MethodInfo constructor = (MethodInfo) MethodInfoTransformer.getInstance().transform(declaredConstructor);
        CodeEmitter e = EmitUtils.begin_method(ce, constructor, Constants.ACC_PUBLIC);
        e.load_this();
        e.dup();
        Signature sig = constructor.getSignature();
        e.super_invoke_constructor(sig);
        e.return_value();
        e.end_method();
    }

    private void emitConstructors(ClassEmitter ce, List constructors) {
        boolean seenNull = false;
        // 遍历持有的构造方法对应的MethodInfo的集合
        for (Iterator it = constructors.iterator(); it.hasNext();) {
            MethodInfo constructor = (MethodInfo)it.next();
            // 如果currentData不为null 并且 当前构造方法的描述符是()V，直接跳过，进行下一次循环
            if (currentData != null && !"()V".equals(constructor.getSignature().getDescriptor())) {
                continue;
            }
            // 否则根据MethodInfo向类中声明一个public的构造方法
            CodeEmitter e = EmitUtils.begin_method(ce, constructor, Constants.ACC_PUBLIC);
            // 向构造方法中添加aload_0字节码，加载this引用到操作数栈顶
            e.load_this();
            // 插入dup字节码，复制栈顶元素
            e.dup();
            // 加载方法参数到操作数栈顶
            e.load_args();
            // 获取构造方法的方法签名
            Signature sig = constructor.getSignature();
            // 判断方法签名的描述符是否是()V，如果是，给seenNull赋值为true，表示访问过参数为null的构造方法
            seenNull = seenNull || sig.getDescriptor().equals("()V");
            // 然后向code中插入invokespecial字节码，调用父类的构造器
            e.super_invoke_constructor(sig);
            // 如果currentData为null
            if (currentData == null) {
                // 插入invokestatic字节码，调用CGLIB$BIND_CALLBACKS(Object)方法，
                // 即调用静态方法将this作为参数传入，进行callbacks的绑定
                e.invoke_static_this(BIND_CALLBACKS);
                // 如果interceptDuringConstruction为false的话
                if (!interceptDuringConstruction) {
                    // 继续向code中插入aload_0字节码
                    e.load_this();
                    // 然后给自身的CGLIB$CONSTRUCTED属性赋值为true
                    e.push(1);
                    e.putfield(CONSTRUCTED_FIELD);
                }
            }
            // 向code中插入return字节码，表示方法返回
            e.return_value();
            e.end_method();
        }
        // 如果classOnly为false 并且 没有发现有无参构造器 并且 参数为null，报错
        if (!classOnly && !seenNull && arguments == null)
            throw new IllegalArgumentException("Superclass has no null constructors but no arguments were given");
    }

    private int[] getCallbackKeys() {
        int[] keys = new int[callbackTypes.length];
        for (int i = 0; i < callbackTypes.length; i++) {
            keys[i] = i;
        }
        return keys;
    }

    private void emitGetCallback(ClassEmitter ce, int[] keys) {
        // 声明一个public Callback getCallback(int index)方法
        final CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, GET_CALLBACK, null);
        // 将this引用加载到栈顶
        e.load_this();
        // 调用CGLIB$BIND_CALLBACKS这个静态方法，将this传入，进行callback的绑定，如果this的CGLIB$BOUND字段为true，
        // 说明已经绑定过，直接返回
        e.invoke_static_this(BIND_CALLBACKS);
        // 再次将this引用加载到栈顶
        e.load_this();
        // 加载第一个参数到栈顶
        e.load_arg(0);
        // 根据keys声明一个switch代码块，根据栈顶的index参数，执行不同的case标签，
        // 逻辑就是获取this引用的CGLIB$CALLBACK_index字段到栈顶。
        // 如果没有对应的case标签，那么将null压入栈顶
        e.process_switch(keys, new ProcessSwitchCallback() {
            public void processCase(int key, Label end) {
                e.getfield(getCallbackField(key));
                e.goTo(end);
            }
            public void processDefault() {
                e.pop(); // stack height
                e.aconst_null();
            }
        });
        // 插入return字节码，返回栈顶元素
        e.return_value();
        e.end_method();
    }

    private void emitSetCallback(ClassEmitter ce, int[] keys) {
        // 声明一个public void setCallback(int index, Callback callback)方法
        final CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, SET_CALLBACK, null);
        // 将第一个参数index加载到栈顶
        e.load_arg(0);
        e.process_switch(keys, new ProcessSwitchCallback() {
            public void processCase(int key, Label end) {
                // 加载this引用到栈顶
                e.load_this();
                // 加载第二个参数，即Callback到栈顶
                e.load_arg(1);
                // 将其强转为CallbackType对应的类型
                e.checkcast(callbackTypes[key]);
                // 然后通过putfield设置进CGLIB$CALLBACK_index字段中
                e.putfield(getCallbackField(key));
                e.goTo(end);
            }
            public void processDefault() {
                // TODO: error?
            }
        });
        // 插入return字节码返回
        e.return_value();
        e.end_method();
    }

    private void emitSetCallbacks(ClassEmitter ce) {
        // 声明一个public void setCallbacks(Callback[] callbacks)方法
        CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, SET_CALLBACKS, null);
        // 加载this引用到栈顶
        e.load_this();
        // 加载第一个参数，即Callback数组到栈顶
        e.load_arg(0);
        // 根据数组长度遍历
        for (int i = 0; i < callbackTypes.length; i++) {
            // 复制栈顶的两个元素
            e.dup2();
            // 获取数组i位置的Callback
            e.aaload(i);
            // 强转检查
            e.checkcast(callbackTypes[i]);
            // 然后将Callback放入到this引用的CGLIB$CALLBACK_i字段中
            e.putfield(getCallbackField(i));
        }
        // 插入return字节码，方法返回
        e.return_value();
        e.end_method();
    }

    private void emitGetCallbacks(ClassEmitter ce) {
        // 声明一个public Callback[] getCallbacks()方法
        CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, GET_CALLBACKS, null);
        // 加载this引用到栈顶
        e.load_this();
        // 调用静态方法CGLIB$BIND_CALLBACKS，将callback绑定到this引用中，如果已经绑定，直接返回
        e.invoke_static_this(BIND_CALLBACKS);
        // 再次加载this引用
        e.load_this();
        // 根据callbackTypes数组的长度创建一个Callback类型的数组
        e.push(callbackTypes.length);
        e.newarray(CALLBACK);
        // 然后根据数组长度遍历
        for (int i = 0; i < callbackTypes.length; i++) {
            // 复制栈顶的数组
            e.dup();
            // 将i压入栈顶
            e.push(i);
            // 获取this的CGLIB$CALLBACK_i字段
            e.load_this();
            e.getfield(getCallbackField(i));
            // 存入到数组i下标中
            e.aastore();
        }
        // 然后返回数组
        e.return_value();
        e.end_method();
    }

    private void emitNewInstanceCallbacks(ClassEmitter ce) {
        // 声明一个public Object newInstance(Callback[] callbacks)方法
        CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, NEW_INSTANCE, null);
        // 获取当前类的type
        Type thisType = getThisType(e);
        // 加载方法的第一个参数到栈顶
        e.load_arg(0);
        // 调用CGLIB$SET_THREAD_CALLBACKS静态方法将栈顶的Callbacks集合设置到CGLIB$THREAD_CALLBACKS这个静态字段持有的ThreadLocal中
        e.invoke_static(thisType, SET_THREAD_CALLBACKS);
        // 然后继续在code中添加处理逻辑
        emitCommonNewInstance(e);
    }

    private Type getThisType(CodeEmitter e) {
        if (currentData == null) {
            return e.getClassEmitter().getClassType();
        } else {
            return Type.getType(currentData.generatedClass);
        }
    }

    private void emitCommonNewInstance(CodeEmitter e) {
        Type thisType = getThisType(e);
        // 添加new字节码，创建一个实例对象
        e.new_instance(thisType);
        // dup复制栈顶对象
        e.dup();
        // 调用栈顶对象的构造方法invokespecial，在构造方法中会将ThreadLocal中的Callback数组绑定给实例
        e.invoke_constructor(thisType);
        // 向栈顶中压入null
        e.aconst_null();
        // 然后将CGLIB$THREAD_CALLBACKS这个静态变量ThreadLocal中持有的值设置为null
        e.invoke_static(thisType, SET_THREAD_CALLBACKS);
        // 插入return字节码，返回创建的实例
        e.return_value();
        e.end_method();
    }
    
    private void emitNewInstanceCallback(ClassEmitter ce) {
        // 声明一个public Object newInstance(Callback callback)方法
        CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, SINGLE_NEW_INSTANCE, null);
        // 判断callbackTypes数组的长度
        switch (callbackTypes.length) {
            // 如果等于0，直接跳出switch
        case 0:
            // TODO: make sure Callback is null
            break;
            // 如果长度等于1，new一个长度为1的Callback数组，将参数传入的Callback放入数组，让后将数组放入CGLIB$THREAD_CALLBACKS这个ThreadLocal中
        case 1:
            // for now just make a new array; TODO: optimize
            e.push(1);
            e.newarray(CALLBACK);
            e.dup();
            e.push(0);
            e.load_arg(0);
            e.aastore();
            e.invoke_static(getThisType(e), SET_THREAD_CALLBACKS);
            break;
            // 如果长度大于1，报错，说明需要多个Callback
        default:
            e.throw_exception(ILLEGAL_STATE_EXCEPTION, "More than one callback object required");
        }
        // 然后向code中生成通用的newInstance的逻辑
        emitCommonNewInstance(e);
    }

    private void emitNewInstanceMultiarg(ClassEmitter ce, List constructors) {
        // 声明一个public Object newInstance(Class[] classes, Object[] objects, Callback[] callbacks)方法
        final CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, MULTIARG_NEW_INSTANCE, null);
        final Type thisType = getThisType(e);
        // 加载第三个方法参数，即Callback数组到栈顶
        e.load_arg(2);
        // 然后设置进CGLIB$THREAD_CALLBACKS这个ThreadLocal中
        e.invoke_static(thisType, SET_THREAD_CALLBACKS);
        // 插入new字节码，实例化代理对象
        e.new_instance(thisType);
        // dup复制代理对象
        e.dup();
        // 将方法第一个参数加载到栈顶
        e.load_arg(0);
        // 通过switch挑选构造方法
        EmitUtils.constructor_switch(e, constructors, new ObjectSwitchCallback() {
            public void processCase(Object key, Label end) {
                MethodInfo constructor = (MethodInfo)key;
                Type types[] = constructor.getSignature().getArgumentTypes();
                for (int i = 0; i < types.length; i++) {
                    e.load_arg(1);
                    e.push(i);
                    e.aaload();
                    e.unbox(types[i]);
                }
                e.invoke_constructor(thisType, constructor.getSignature());
                e.goTo(end);
            }
            public void processDefault() {
                e.throw_exception(ILLEGAL_ARGUMENT_EXCEPTION, "Constructor not found");
            }
        });
        // 将CGLIB$THREAD_CALLBACKS这个ThreadLocal中的元素置为null
        e.aconst_null();
        e.invoke_static(thisType, SET_THREAD_CALLBACKS);
        // 插入return字节码，返回实例对象
        e.return_value();
        e.end_method();
    }

    private void emitMethods(final ClassEmitter ce, List<MethodInfo> methods, List<Method> actualMethods) {
        // 根据callbackTypes数组获取对应的CallbackGenerator数组
        CallbackGenerator[] generators = CallbackInfo.getGenerators(callbackTypes);

        Map<CallbackGenerator, List<MethodInfo>> groups = new HashMap();
        // key为MethodInfo，value为需要应用的Callback的index
        final Map<MethodInfo, Integer> indexes = new HashMap();
        // key为MethodInfo，value为方法原本的access_flags
        final Map<MethodInfo, Integer> originalModifiers = new HashMap();
        // 将methods集合转换为IndexMap，key为MethodInfo，value为其在集合中的索引
        final Map<MethodInfo, Integer> positions = CollectionUtils.getIndexMap(methods);
        // key为声明方法的Class，value为类中所有的桥接方法的签名组成的set
        final Map<Class<?>, Set<Signature>> declToBridge = new HashMap();

        // 迭代器1为MethodInfo集合的迭代器
        Iterator<MethodInfo> it1 = methods.iterator();
        // 迭代器2为Method集合的迭代器
        Iterator<Method> it2 = (actualMethods != null) ? actualMethods.iterator() : null;

        // 根据迭代器1进行迭代
        while (it1.hasNext()) {
            // 获取MethodInfo
            MethodInfo method = (MethodInfo)it1.next();
            // 以及对应的Method对象
            Method actualMethod = (it2 != null) ? (Method)it2.next() : null;
            // 通过CallbackFilter计算该方法应该应用哪一个Callback，返回其下标
            int index = filter.accept(actualMethod);
            // 如果Callback的位置大于了callbackTypes的长度，报错
            if (index >= callbackTypes.length) {
                throw new IllegalArgumentException("Callback filter returned an index that is too large: " + index);
            }
            // 向originalModifiers这个map中放入method对应的actualMethod的原始的访问修饰符，如果不存在actualMethod，就返回method自身的modifiers
            originalModifiers.put(method, new Integer((actualMethod != null) ? actualMethod.getModifiers() : method.getModifiers()));
            // 向indexes这个map中存入MethodInfo对应的需要应用的Callback的index
            indexes.put(method, new Integer(index));
            // 通过index对应的CallbackGenerator从groups这个map中获取对应的group
            List<MethodInfo> group = groups.get(generators[index]);
            // 如果group为null的话，创建一个list以CallbackGenerator为key放入groups这个map中
            if (group == null) {
                groups.put(generators[index], group = new ArrayList(methods.size()));
            }
            // 将method添加进group中
            group.add(method);
            
            // Optimization: build up a map of Class -> bridge methods in class
            // so that we can look up all the bridge methods in one pass for a class.
            // 构建一个key为class，value为对应class中的所有桥接方法的map
            // 如果actualMethod的访问修饰符判断出存在bridge
            if (TypeUtils.isBridge(actualMethod.getModifiers())) {
                // 从map中根据声明这个方法的类获取到对应的桥接方法set
            	Set<Signature> bridges = declToBridge.get(actualMethod.getDeclaringClass());
                // 如果set不存在，初始化一个，并且放入map中
            	if (bridges == null) {
            	    bridges = new HashSet();
            	    declToBridge.put(actualMethod.getDeclaringClass(), bridges);
            	}
                // 从MethodInfo中获取Signature放入set中
            	bridges.add(method.getSignature());            	
            }
        }

        // 创建一个桥接方法解析器对declToBridge这个map进行解析，获得那些在已知的桥接方法中通过invokespecial和invokeinterface进行调用的那些方法的签名。
        // 会将和桥接方法签名相等的那些签名排除掉，这种情况只会发生在用于扩展方法的可见性的时候。
        // 其中key为桥接方法的签名，value为被桥接方法通过invokespecial或invokeinterface调用的方法的签名
        final Map<Signature, Signature> bridgeToTarget = new BridgeMethodResolver(declToBridge, getClassLoader()).resolveAll();

        Set<CallbackGenerator> seenGen = new HashSet();
        // 获取class的CGLIB$STATICHOOK方法的codeEmitter
        CodeEmitter se = ce.getStaticHook();
        // 向该方法的code属性中添加字节码
        // 添加new字节码，创建一个ThreadLocal类型
        se.new_instance(THREAD_LOCAL);
        // 添加dup字节码
        se.dup();
        // 添加invokespecial字节码，调用ThreadLocal类的无参构造方法
        se.invoke_constructor(THREAD_LOCAL, CSTRUCT_NULL);
        // 将创建出来的ThreadLocal对象放入类的CGLIB$THREAD_CALLBACKS字段中，因为是静态属性，所以字节码是putstatic
        se.putfield(THREAD_CALLBACKS_FIELD);

        final Object[] state = new Object[1];
        // 创建一个CallbackGenerator的内部接口Context的匿名实现类
        CallbackGenerator.Context context = new CallbackGenerator.Context() {
            // 返回加载Enhancer这个类的类加载器
            public ClassLoader getClassLoader() {
                return Enhancer.this.getClassLoader();
            }
            // 获取method对应的原始访问修饰符
            public int getOriginalModifiers(MethodInfo method) {
                return ((Integer)originalModifiers.get(method)).intValue();
            }
            // 获取method对应的callback的索引
            public int getIndex(MethodInfo method) {
                return ((Integer)indexes.get(method)).intValue();
            }
            // 获取index对应的类中的CGLIB$CALLBACK_index属性，如果为null的话，调用static void CGLIB$BIND_CALLBACKS(Object o)方法进行绑定
            public void emitCallback(CodeEmitter e, int index) {
                emitCurrentCallback(e, index);
            }
            // 根据methodInfo里的原本的方法签名和方法所在的位置，对方法进行重命名，并返回新的方法签名。
            // 重命名的规则为CGLIB$ + originalName + $ + position
            public Signature getImplSignature(MethodInfo method) {
                return rename(method.getSignature(), ((Integer)positions.get(method)).intValue());
            }
            public void emitLoadArgsAndInvoke(CodeEmitter e, MethodInfo method) {
                // If this is a bridge and we know the target was called from invokespecial,
                // then we need to invoke_virtual w/ the bridge target instead of doing
                // a super, because super may itself be using super, which would bypass
                // any proxies on the target.
                // 如果当前方法是一个我们已知的调用invokespecial的桥接方法，那么我们需要将其转换为invokevirtual的，
                // 确保不会绕过代理
                Signature bridgeTarget = (Signature)bridgeToTarget.get(method.getSignature());
                // 如果bridgeTarget存在，将每个参数的类型都强转为targetMethod的参数类型
                if (bridgeTarget != null) {
                    // checkcast each argument against the target's argument types
                    for (int i = 0; i < bridgeTarget.getArgumentTypes().length; i++) {
                        e.load_arg(i);
                        Type target = bridgeTarget.getArgumentTypes()[i];
                        if (!target.equals(method.getSignature().getArgumentTypes()[i])) {
                            e.checkcast(target);
                        }
                    }

                    // 然后向方法的code属性中添加invokevirtual字节码 目标方法就是bridgeTarget
                    e.invoke_virtual_this(bridgeTarget);
                    
                    Type retType = method.getSignature().getReturnType();                    
                    // Not necessary to cast if the target & bridge have
                    // the same return type. 
                    // (This conveniently includes void and primitive types,
                    // which would fail if casted.  It's not possible to 
                    // covariant from boxed to unbox (or vice versa), so no having
                    // to box/unbox for bridges).
                    // TODO: It also isn't necessary to checkcast if the return is
                    // assignable from the target.  (This would happen if a subclass
                    // used covariant returns to narrow the return type within a bridge
                    // method.)
                    // 如果当前方法的返回值类型和目标方法返回值类型不一致，将其强转为当前方法的返回值类型
                    if (!retType.equals(bridgeTarget.getReturnType())) {
                        e.checkcast(retType);
                    }
                }
                // 如果不是invokespecial的桥接方法，那么直接加载参数，调用invokespecial字节码，调用父类的同签名的方法
                else {
                    e.load_args();
                    e.super_invoke(method.getSignature());
                }
            }
            public CodeEmitter beginMethod(ClassEmitter ce, MethodInfo method) {
                // 声明一个MethodInfo所包含的访问修饰符 方法名 和描述符的方法
                CodeEmitter e = EmitUtils.begin_method(ce, method);
                // 如果interceptDuringConstruction为false 并且 方法访问修饰符不是abstract的
                if (!interceptDuringConstruction &&
                    !TypeUtils.isAbstract(method.getModifiers())) {
                    // 创建一个label
                    Label constructed = e.make_label();
                    // 向方法的code中添加aload_0字节码，加载this引用到操作数栈
                    e.load_this();
                    // 然后添加getfield字节码 获取CGLIB$CONSTRUCTED字段到操作数栈顶
                    e.getfield(CONSTRUCTED_FIELD);
                    // 如果该字段的值不等于false的话，就进行跳转到mark的位置，否则执行if代码块里的逻辑。
                    // 即如果该字段的值为false的话，执行if逻辑
                    e.if_jump(e.NE, constructed);
                    // aload_0
                    e.load_this();
                    // load方法的所有参数，除了this引用
                    e.load_args();
                    // 然后添加invokespecial，调用父类中同签名的方法
                    e.super_invoke();
                    // 添加return相关的字节码，返回结果
                    e.return_value();
                    // 表示constructed这个label到这里结束
                    e.mark(constructed);
                }
                return e;
            }
        };
        // 遍历持有的callbackTypes
        for (int i = 0; i < callbackTypes.length; i++) {
            // 找到对应的CallbackGenerator
            CallbackGenerator gen = generators[i];
            // 如果已经查看过的Generator集合中不包含该generator，那么调用generator进行生成
            if (!seenGen.contains(gen)) {
                // 将generator添加到seenGen这个set中，表示已经访问过
                seenGen.add(gen);
                // 从方法分组中根据generator获取到需要使用该generator的方法集合
                final List<MethodInfo> fmethods = groups.get(gen);
                // 如果方法集合不为null
                if (fmethods != null) {
                    try {
                        // 调用callbackGenerator的generate方法，将classEmitter generator上下文 方法集合传入
                        gen.generate(ce, context, fmethods);
                        // 然后再调用callbackGenerator的generateStatic方法，将CGLIB$STATICHOOK方法对应的CodeEmitter传入，
                        // 同时也将上下文和方法集合传入
                        gen.generateStatic(se, context, fmethods);
                    } catch (RuntimeException x) {
                        throw x;
                    } catch (Exception x) {
                        throw new CodeGenerationException(x);
                    }
                }
            }
        }
        // 向CGLIB$STATICHOOK方法中插入return相关的字节码
        se.return_value();
        // 然后调用CGLIB$STATICHOOK对应的codeEmitter的end_method，表示方法结束，计算maxlocals maxstack等数据
        se.end_method();
    }

    private void emitSetThreadCallbacks(ClassEmitter ce) {
        // 在类中声明一个public static void CGLIB$SET_THREAD_CALLBACKS(Callback[] callbacks)的方法
        CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC | Constants.ACC_STATIC,
                                        SET_THREAD_CALLBACKS,
                                        null);
        // 向code中插入getstatic字节码，获取private static final Thread CGLIB$THREAD_CALLBACKS引用指向的对象到操作数栈顶
        e.getfield(THREAD_CALLBACKS_FIELD);
        // 然后加载第一个参数到操作数栈顶，即Callback类型的数组
        e.load_arg(0);
        // 然后调用ThreadLocal的set方法，将数组设置进去
        e.invoke_virtual(THREAD_LOCAL, THREAD_LOCAL_SET);
        // 然后插入return字节码返回
        e.return_value();
        e.end_method();
    }

    private void emitSetStaticCallbacks(ClassEmitter ce) {
        // 在类中声明一个public static void CGLIB$SET_STATIC_CALLBACKS(Callback[] callbacks)的方法
        CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC | Constants.ACC_STATIC,
                                        SET_STATIC_CALLBACKS,
                                        null);
        // 加载方法的第一个参数，即Callback类型的数组到操作数栈顶
        e.load_arg(0);
        // 向code中插入putstatic字节码，将栈顶的Callback数组放入到private static final Callback[] CGLIB$STATIC_CALLBACKS字段中
        e.putfield(STATIC_CALLBACKS_FIELD);
        // 向code中插入return字节码，返回
        e.return_value();
        e.end_method();
    }
    
    private void emitCurrentCallback(CodeEmitter e, int index) {
        // 添加aload_0字节码，加载this引用
        e.load_this();
        // 添加getfield字节码 获取自身持有的CGLIB$CALLBACK_{index}字段
        e.getfield(getCallbackField(index));
        // 添加dup字节码，复制栈顶元素
        e.dup();
        // 创建label
        Label end = e.make_label();
        // 如果栈顶元素不为null的话，直接跳到end标签位置
        e.ifnonnull(end);
        // 如果栈顶元素为null，即对应的callback引用指向null
        // 将栈顶元素出栈，栈顶元素此时是if_notnull字节码的比较结果
        e.pop(); // stack height
        // 添加aload_0字节码
        e.load_this();
        // 添加invokestatic字节码，调用自身的CGLIB$BIND_CALLBACKS方法
        e.invoke_static_this(BIND_CALLBACKS);
        // 再次添加aload_0字节码
        e.load_this();
        // 添加getfield字节码 重新获取callback字段
        e.getfield(getCallbackField(index));
        // 插入end标签
        e.mark(end);
    }

    private void emitBindCallbacks(ClassEmitter ce) {
        // 在类中声明private static final void CGLIB$BIND_CALLBACKS(Object proxy)方法
        CodeEmitter e = ce.begin_method(Constants.PRIVATE_FINAL_STATIC,
                BIND_CALLBACKS,
                null);
        // 创建一个me局部变量
        Local me = e.make_local();
        // 加载方法的第一个参数到操作数栈顶，也就是代理对象proxy
        e.load_arg(0);
        // 然后检查其是否可以强转为自身类型
        e.checkcast_this();
        // 然后将proxy对象存入到me局部变量slot中
        e.store_local(me);

        // 创建end标签
        Label end = e.make_label();
        // 加载局部变量me到操作数栈顶
        e.load_local(me);
        // 插入getfield字节码，获取其private boolean CGLIB$BOUND字段
        e.getfield(BOUND_FIELD);
        // 如果该字段的值不等于0，也就是等于true，直接跳到end标签的位置；
        // 如果该字段等于false，进行下面的逻辑
        e.if_jump(e.NE, end);
        // 继续加载me到栈顶
        e.load_local(me);
        e.push(1);
        // 插入putfield，将其CGLIB$BOUND字段设置为true
        e.putfield(BOUND_FIELD);

        // 然后插入getstatic字节码，获取private static final ThreadLocal CGLIB$THREAD_CALLBACKS字段
        e.getfield(THREAD_CALLBACKS_FIELD);
        // 然后插入invokevirtual字节码，调用ThreadLocal的get方法，将返回的Callback数组压入栈顶
        e.invoke_virtual(THREAD_LOCAL, THREAD_LOCAL_GET);
        // dup字节码，复制栈顶元素
        e.dup();
        // 创建一个found_callback标签
        Label found_callback = e.make_label();
        // 如果栈顶的元素不为null的话，跳转到found_callback标签的位置
        e.ifnonnull(found_callback);
        // 如果为null，将栈顶元素弹出
        e.pop();

        // 然后插入getstatic字节码，获取private static final Callback[] CGLIB$STATIC_CALLBACKS字段
        e.getfield(STATIC_CALLBACKS_FIELD);
        // 复制栈顶元素
        e.dup();
        // 判断栈顶元素是否为null，如果不为null，跳转到found_callback标签
        e.ifnonnull(found_callback);
        // 如果为null，弹出栈顶元素
        e.pop();
        // 然后直接跳转到end标签
        e.goTo(end);

        // 将found_callback标签标记在这里
        e.mark(found_callback);
        // 将栈顶元素强转为Callback[]类型的
        e.checkcast(CALLBACK_ARRAY);
        // 然后加载me局部变量到栈顶，此时栈顶元素依次是me callbacks
        e.load_local(me);
        // 将栈顶的两个元素交换，交换之后变成callbacks me
        e.swap();
        // 根据callbackTypes进行倒序遍历
        for (int i = callbackTypes.length - 1; i >= 0; i--) {
            // 当i不为0的时候，都将栈顶的两个字复制一遍重新压回栈顶，此时栈顶是callbacks me callbacks me
            if (i != 0) {
                e.dup2();
            }
            // 然后获取callbacks在下标i的元素
            e.aaload(i);
            // 进行强转为对应callbackType的检查
            e.checkcast(callbackTypes[i]);
            // 然后调用putfield，将callback放入private Callback CGLIB$CALLBACK_i字段
            e.putfield(getCallbackField(i));
        }

        // 将end标签标记在这里
        e.mark(end);
        // 然后插入return字节码
        e.return_value();
        e.end_method();

        // 整个方法插入的字节码翻译成Java代码就是
//        private static final void CGLIB$BIND_CALLBACKS(Object proxy) {
//            Proxy me = (Proxy) proxy;
//            if (!me.CGLIB$BOUND) {
//                me.CGLIB$BOUND = true;
//                Callback[] callbacks = CGLIB$THREAD_CALLBACKS.get();
//                if (callbacks == null) {
//                    callbacks = (Callback[]) CGLIB$STATIC_CALLBACKS;
//                }
//                if (callbacks == null)
//                    return;
//                for (int i = callbacks.length - 1; i >= 0; i--) {
//                    me.CGLIB$CALLBACK_i = callbacks[i];
//                }
//            }
//        }
    }

    private static String getCallbackField(int index) {
        return "CGLIB$CALLBACK_" + index;
    }
}
