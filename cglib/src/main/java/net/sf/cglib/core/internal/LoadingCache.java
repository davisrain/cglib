package net.sf.cglib.core.internal;

import java.util.concurrent.*;

public class LoadingCache<K, KK, V> {
    protected final ConcurrentMap<KK, Object> map;
    protected final Function<K, V> loader;
    protected final Function<K, KK> keyMapper;

    public static final Function IDENTITY = new Function() {
        public Object apply(Object key) {
            return key;
        }
    };

    public LoadingCache(Function<K, KK> keyMapper, Function<K, V> loader) {
        // 用于将K类型映射为KK类型
        this.keyMapper = keyMapper;
        // 用于根据K类型获取对应的V类型
        this.loader = loader;
        this.map = new ConcurrentHashMap<KK, Object>();
    }

    @SuppressWarnings("unchecked")
    public static <K> Function<K, K> identity() {
        return IDENTITY;
    }

    public V get(K key) {
        // 先将key转换为KK类型，即缓存键
        final KK cacheKey = keyMapper.apply(key);
        // 根据cacheKey去map中查找对应的value
        Object v = map.get(cacheKey);
        // 如果value不为null 并且 value不是FutureTask类型的，直接返回
        if (v != null && !(v instanceof FutureTask)) {
            return (V) v;
        }

        // 否则创建对应的value
        return createEntry(key, cacheKey, v);
    }

    /**
     * Loads entry to the cache.
     * If entry is missing, put {@link FutureTask} first so other competing thread might wait for the result.
     * @param key original key that would be used to load the instance
     * @param cacheKey key that would be used to store the entry in internal map
     * @param v null or {@link FutureTask<V>}
     * @return newly created instance
     */
    protected V createEntry(final K key, KK cacheKey, Object v) {
        FutureTask<V> task;
        boolean creator = false;
        // 如果v不为null，那么一定是FutureTask类型的，直接赋值给task
        if (v != null) {
            // Another thread is already loading an instance
            task = (FutureTask<V>) v;
        } else {
            // 创建一个FutureTask，里面的callable逻辑是调用loader对value进行加载
            task = new FutureTask<V>(new Callable<V>() {
                public V call() throws Exception {
                    return loader.apply(key);
                }
            });
            // 将task作为value放入map中，并返回cacheKey对应的前置任务
            Object prevTask = map.putIfAbsent(cacheKey, task);
            // 如果前置任务为null
            if (prevTask == null) {
                // creator does the load
                // 那么直接对value进行创建，调用task的run方法
                creator = true;
                task.run();
            }
            // 如果前置任务存在且是FutureTask类型的，将其赋值给task
            else if (prevTask instanceof FutureTask) {
                task = (FutureTask<V>) prevTask;
            }
            // 如果前置任务不是FutureTask类型的，直接返回
            else {
                return (V) prevTask;
            }
        }

        V result;
        try {
            // 调用task的get方法
            result = task.get();
        } catch (InterruptedException e) {
            throw new IllegalStateException("Interrupted while loading cache item", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw ((RuntimeException) cause);
            }
            throw new IllegalStateException("Unable to load cache item", cause);
        }
        // 如果creator标志为true，将最终的结果放入缓存中
        if (creator) {
            map.put(cacheKey, result);
        }
        return result;
    }
}
