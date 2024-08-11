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

import net.sf.cglib.core.internal.Function;
import net.sf.cglib.core.internal.LoadingCache;
import org.objectweb.asm.ClassReader;

import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.WeakHashMap;

/**
 * Abstract class for all code-generating CGLIB utilities.
 * In addition to caching generated classes for performance, it provides hooks for
 * customizing the <code>ClassLoader</code>, name of the generated class, and transformations
 * applied before generation.
 */
abstract public class AbstractClassGenerator<T>
implements ClassGenerator
{
    private static final ThreadLocal CURRENT = new ThreadLocal();

    private static volatile Map<ClassLoader, ClassLoaderData> CACHE = new WeakHashMap<ClassLoader, ClassLoaderData>();

    private static final boolean DEFAULT_USE_CACHE =
        Boolean.parseBoolean(System.getProperty("cglib.useCache", "true"));

    // 默认是DefaultGeneratorStrategy
    private GeneratorStrategy strategy = DefaultGeneratorStrategy.INSTANCE;
    // 默认是DefaultNamingPolicy
    private NamingPolicy namingPolicy = DefaultNamingPolicy.INSTANCE;
    // source对象只包含一个name属性，表示的就是AbstractClassGenerator的来源。
    // 比如可能是Enhancer 也可能是KeyFactory中的Generator
    private Source source;
    private ClassLoader classLoader;

    // 生成的类的类名前缀
    private String namePrefix;
    // 用于判断是否能够命中缓存的key，如果AbstractClassGenerator中的key相等的话，就能够使用缓存在
    // ClassLoaderData里面的类，而不用再次进行类的创建了
    private Object key;
    // 默认是使用缓存的，即会使用ClassLoaderData里面与key属性匹配的缓存
    private boolean useCache = DEFAULT_USE_CACHE;
    private String className;
    private boolean attemptLoad;

    protected static class ClassLoaderData {
        // 用于保存已经解析过了的类名
        private final Set<String> reservedClassNames = new HashSet<String>();

        /**
         * {@link AbstractClassGenerator} here holds "cache key" (e.g. {@link net.sf.cglib.proxy.Enhancer}
         * configuration), and the value is the generated class plus some additional values
         * (see {@link #unwrapCachedValue(Object)}.
         * <p>The generated classes can be reused as long as their classloader is reachable.</p>
         * <p>Note: the only way to access a class is to find it through generatedClasses cache, thus
         * the key should not expire as long as the class itself is alive (its classloader is alive).</p>
         */
        // 注意：访问一个class的唯一方式是去通过generatedClasses缓存查找，因此如果class自身是存活状态的话，缓存对应的key就不会过期
        private final LoadingCache<AbstractClassGenerator, Object, Object> generatedClasses;

        /**
         * Note: ClassLoaderData object is stored as a value of {@code WeakHashMap<ClassLoader, ...>} thus
         * this classLoader reference should be weak otherwise it would make classLoader strongly reachable
         * and alive forever.
         * Reference queue is not required since the cleanup is handled by {@link WeakHashMap}.
         *
         * 因为ClassLoaderData被保存在一个弱引用的hashmap中，它的key，即classLoader是弱引用，因此这里的classloader也需要是弱引用。
         * 否则的话，会将classloader变成强引用并一直存活
         *
         * reference queue是不需要的，因此cleanup操作会被weakHashMap处理
         */
        private final WeakReference<ClassLoader> classLoader;

        // 唯一名称判断，用于检查传入的name是否已经存在了
        private final Predicate uniqueNamePredicate = new Predicate() {
            public boolean evaluate(Object name) {
                return reservedClassNames.contains(name);
            }
        };

        // 包装一个函数操作作为常量。
        // 具体逻辑是返回AbstractClassGenerator的key属性
        private static final Function<AbstractClassGenerator, Object> GET_KEY = new Function<AbstractClassGenerator, Object>() {
            public Object apply(AbstractClassGenerator gen) {
                return gen.key;
            }
        };

        public ClassLoaderData(ClassLoader classLoader) {
            if (classLoader == null) {
                throw new IllegalArgumentException("classLoader == null is not yet supported");
            }
            // 为classloader创建一个弱引用
            this.classLoader = new WeakReference<ClassLoader>(classLoader);
            // 包装一个函数操作，具体逻辑是：
            // 1.调用AbstractClassGenerator的generate方法创建出代理类，该方法的参数就是ClassLoaderData自身
            // 2.然后继续调用AbstractClassGenerator的wrapCachedClass方法对代理类进行包装
            Function<AbstractClassGenerator, Object> load =
                    new Function<AbstractClassGenerator, Object>() {
                        public Object apply(AbstractClassGenerator gen) {
                            // 调用generator的generate方法，将classloaderData传入
                            Class klass = gen.generate(ClassLoaderData.this);
                            // 将得到的class进行包装，默认实现是包装为WeakReference
                            return gen.wrapCachedClass(klass);
                        }
                    };
            // 将getkey 和 load这两个函数操作封装成一个LoadingCache对象赋值给generatedClasses属性
            generatedClasses = new LoadingCache<AbstractClassGenerator, Object, Object>(GET_KEY, load);
        }

        public ClassLoader getClassLoader() {
            return classLoader.get();
        }

        public void reserveName(String name) {
            reservedClassNames.add(name);
        }

        public Predicate getUniqueNamePredicate() {
            return uniqueNamePredicate;
        }

        public Object get(AbstractClassGenerator gen, boolean useCache) {
            // 如果不使用缓存
            if (!useCache) {
                // 直接调用generator的generate方法，将classLoaderData传入
              return gen.generate(ClassLoaderData.this);
            }
            // 如果要使用缓存
            else {
                // 尝试从generatedClasses中获取对应的value
              Object cachedValue = generatedClasses.get(gen);
              // 然后调用unwrapCachedValue将获取的value进行解包装，然后返回
              return gen.unwrapCachedValue(cachedValue);
            }
        }
    }

    protected T wrapCachedClass(Class klass) {
        return (T) new WeakReference(klass);
    }

    protected Object unwrapCachedValue(T cached) {
        return ((WeakReference) cached).get();
    }

    protected static class Source {
        String name;
        public Source(String name) {
            this.name = name;
        }
    }

    protected AbstractClassGenerator(Source source) {
        this.source = source;
    }

    protected void setNamePrefix(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    final protected String getClassName() {
        return className;
    }

    private void setClassName(String className) {
        this.className = className;
    }

    private String generateClassName(Predicate nameTestPredicate) {
        // 使用自身的namingPolicy获取要生成的代理类的类名，传入namePrefix，source.name，以及key作为参数，并且传入了一个Predicate用于检验类名
        return namingPolicy.getClassName(namePrefix, source.name, key, nameTestPredicate);
    }

    /**
     * Set the <code>ClassLoader</code> in which the class will be generated.
     * Concrete subclasses of <code>AbstractClassGenerator</code> (such as <code>Enhancer</code>)
     * will try to choose an appropriate default if this is unset.
     * <p>
     * Classes are cached per-<code>ClassLoader</code> using a <code>WeakHashMap</code>, to allow
     * the generated classes to be removed when the associated loader is garbage collected.
     * @param classLoader the loader to generate the new class with, or null to use the default
     */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Override the default naming policy.
     * @see DefaultNamingPolicy
     * @param namingPolicy the custom policy, or null to use the default
     */
    public void setNamingPolicy(NamingPolicy namingPolicy) {
        if (namingPolicy == null)
            namingPolicy = DefaultNamingPolicy.INSTANCE;
        this.namingPolicy = namingPolicy;
    }

    /**
     * @see #setNamingPolicy
     */
    public NamingPolicy getNamingPolicy() {
        return namingPolicy;
    }

    /**
     * Whether use and update the static cache of generated classes
     * for a class with the same properties. Default is <code>true</code>.
     */
    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
    }

    /**
     * @see #setUseCache
     */
    public boolean getUseCache() {
        return useCache;
    }

    /**
     * If set, CGLIB will attempt to load classes from the specified
     * <code>ClassLoader</code> before generating them. Because generated
     * class names are not guaranteed to be unique, the default is <code>false</code>.
     */
    public void setAttemptLoad(boolean attemptLoad) {
        this.attemptLoad = attemptLoad;
    }

    public boolean getAttemptLoad() {
        return attemptLoad;
    }
    
    /**
     * Set the strategy to use to create the bytecode from this generator.
     * By default an instance of {@see DefaultGeneratorStrategy} is used.
     */
    public void setStrategy(GeneratorStrategy strategy) {
        if (strategy == null)
            strategy = DefaultGeneratorStrategy.INSTANCE;
        this.strategy = strategy;
    }

    /**
     * @see #setStrategy
     */
    public GeneratorStrategy getStrategy() {
        return strategy;
    }

    /**
     * Used internally by CGLIB. Returns the <code>AbstractClassGenerator</code>
     * that is being used to generate a class in the current thread.
     */
    public static AbstractClassGenerator getCurrent() {
        return (AbstractClassGenerator)CURRENT.get();
    }

    public ClassLoader getClassLoader() {
        ClassLoader t = classLoader;
        if (t == null) {
            t = getDefaultClassLoader();
        }
        if (t == null) {
            t = getClass().getClassLoader();
        }
        if (t == null) {
            t = Thread.currentThread().getContextClassLoader();
        }
        if (t == null) {
            throw new IllegalStateException("Cannot determine classloader");
        }
        return t;
    }

    abstract protected ClassLoader getDefaultClassLoader();

    /**
     * Returns the protection domain to use when defining the class.
     * <p>
     * Default implementation returns <code>null</code> for using a default protection domain. Sub-classes may
     * override to use a more specific protection domain.
     * </p>
     *
     * @return the protection domain (<code>null</code> for using a default)
     */
    protected ProtectionDomain getProtectionDomain() {
    	return null;
    }

    protected Object create(Object key) {
        try {
            // 获取classloader
            ClassLoader loader = getClassLoader();
            // 尝试从缓存中获取classloader对应的classloaderData
            Map<ClassLoader, ClassLoaderData> cache = CACHE;
            ClassLoaderData data = cache.get(loader);
            // 如果缓存未命中
            if (data == null) {
                // 加锁
                synchronized (AbstractClassGenerator.class) {
                    // double check
                    cache = CACHE;
                    data = cache.get(loader);
                    // 如果缓存仍然未命中
                    if (data == null) {
                        // 根据classloader创建一个classLoaderData，放入缓存中
                        Map<ClassLoader, ClassLoaderData> newCache = new WeakHashMap<ClassLoader, ClassLoaderData>(cache);
                        data = new ClassLoaderData(loader);
                        newCache.put(loader, data);
                        CACHE = newCache;
                    }
                }
            }
            // 将传入的key设置进自身属性中
            this.key = key;
            // 调用classloaderData的get方法，最后会调用到abstractClassGenerator的generate方法，
            // 然后根据generatorStrategy调用到generator的generateClass方法，再通过defineClass加载为类对象
            Object obj = data.get(this, getUseCache());
            // 如果obj是class类型的，调用firstInstance方法进行实例化，该方法是一个模板方法，由子类实现
            if (obj instanceof Class) {
                return firstInstance((Class) obj);
            }
            // 如果不是class类型的，调用nextInstance方法返回，该方法也是一个模板方法
            return nextInstance(obj);
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Exception e) {
            throw new CodeGenerationException(e);
        }
    }

    protected Class generate(ClassLoaderData data) {
        Class gen;
        // 获取当前ThreadLocal中保存的内容
        Object save = CURRENT.get();
        // 将AbstractClassGenerator类型的自身实例存入ThreadLocal中
        CURRENT.set(this);
        try {
            // 获取data的classloader
            ClassLoader classLoader = data.getClassLoader();
            // 如果classloader为null，报错
            if (classLoader == null) {
                throw new IllegalStateException("ClassLoader is null while trying to define class " +
                        getClassName() + ". It seems that the loader has been expired from a weak reference somehow. " +
                        "Please file an issue at cglib's issue tracker.");
            }
            synchronized (classLoader) {
                // 根据namingPolicy生成代理类的类名
                String name = generateClassName(data.getUniqueNamePredicate());
                // 将生成的类名保存进classloaderData的reserveNames集合中，以便进行类名唯一性的判断
                data.reserveName(name);
                // 设置类名
                this.setClassName(name);
            }
            // 尝试在generate之前根据类名去使用classloader去加载对应的类
            if (attemptLoad) {
                try {
                    // 如果加载成功，直接返回
                    gen = classLoader.loadClass(getClassName());
                    return gen;
                } catch (ClassNotFoundException e) {
                    // ignore
                    // 如果没有找到对应的类，忽略掉异常
                }
            }
            // 尝试用持有的generateStrategy去生成对应的class文件的字节数组，其中传入自身ClassGenerator作为参数
            // 具体的逻辑会调用到generator的generateClass方法，向classWriter中生成class文件所需要的二进制
            byte[] b = strategy.generate(this);
            // 使用一个ClassVisitor去读取class文件字节数组中this_class的名称，将/转换为.作为类名
            String className = ClassNameReader.getClassName(new ClassReader(b));
            // 获取类的保护域
            ProtectionDomain protectionDomain = getProtectionDomain();
            synchronized (classLoader) { // just in case
                // 根据是否存在保护域，选择不同的加载类对象的方法
                if (protectionDomain == null) {
                    gen = ReflectUtils.defineClass(className, b, classLoader);
                } else {
                    gen = ReflectUtils.defineClass(className, b, classLoader, protectionDomain);
                }
            }
            // 返回加载后的类
            return gen;
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Exception e) {
            throw new CodeGenerationException(e);
        } finally {
            // 最后将之前ThreadLocal中保存的内容设置回去
            CURRENT.set(save);
        }
    }

    abstract protected Object firstInstance(Class type) throws Exception;
    abstract protected Object nextInstance(Object instance) throws Exception;
}
