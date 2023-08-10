/*
 * Copyright 2004 The Apache Software Foundation
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

import org.objectweb.asm.Type;

class CallbackInfo
{
    public static Type[] determineTypes(Class[] callbackTypes) {
        return determineTypes(callbackTypes, true);
    }

    public static Type[] determineTypes(Class[] callbackTypes, boolean checkAll) {
        Type[] types = new Type[callbackTypes.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = determineType(callbackTypes[i], checkAll);
        }
        return types;
    }

    public static Type[] determineTypes(Callback[] callbacks) {
        return determineTypes(callbacks, true);
    }

    public static Type[] determineTypes(Callback[] callbacks, boolean checkAll) {
        // 创建一个Type数组，根据callbacks的长度
        Type[] types = new Type[callbacks.length];
        // 遍历callbacks，根据每个callback获取其type类型，放入到对应的下标中
        for (int i = 0; i < types.length; i++) {
            types[i] = determineType(callbacks[i], checkAll);
        }
        return types;
    }

    public static CallbackGenerator[] getGenerators(Type[] callbackTypes) {
        CallbackGenerator[] generators = new CallbackGenerator[callbackTypes.length];
        // 遍历callbackTypes数组
        for (int i = 0; i < generators.length; i++) {
            // 依次调用getGenerator方法获取对应的generator放入相应下标中
            generators[i] = getGenerator(callbackTypes[i]);
        }
        return generators;
    }

    //////////////////// PRIVATE ////////////////////

    private Class cls;
    private CallbackGenerator generator;
    private Type type;
    
    private static final CallbackInfo[] CALLBACKS = {
        new CallbackInfo(NoOp.class, NoOpGenerator.INSTANCE),
        new CallbackInfo(MethodInterceptor.class, MethodInterceptorGenerator.INSTANCE),
        new CallbackInfo(InvocationHandler.class, InvocationHandlerGenerator.INSTANCE),
        new CallbackInfo(LazyLoader.class, LazyLoaderGenerator.INSTANCE),
        new CallbackInfo(Dispatcher.class, DispatcherGenerator.INSTANCE),
        new CallbackInfo(FixedValue.class, FixedValueGenerator.INSTANCE),
        new CallbackInfo(ProxyRefDispatcher.class, DispatcherGenerator.PROXY_REF_INSTANCE),
    };

    private CallbackInfo(Class cls, CallbackGenerator generator) {
        this.cls = cls;
        this.generator = generator;
        type = Type.getType(cls);
    }

    private static Type determineType(Callback callback, boolean checkAll) {
        // 如果callback为null，报错
        if (callback == null) {
            throw new IllegalStateException("Callback is null");
        }
        return determineType(callback.getClass(), checkAll);
    }

    private static Type determineType(Class callbackType, boolean checkAll) {
        Class cur = null;
        Type type = null;
        // 遍历持有的CallbackInfo数组
        for (int i = 0; i < CALLBACKS.length; i++) {
            CallbackInfo info = CALLBACKS[i];
            // 如果存在某个callback的type可以赋值给callbackInfo持有的class
            if (info.cls.isAssignableFrom(callbackType)) {
                // 如果当前存在的class已经不为null，说明之前已经匹配到对应的类型了，那么报错
                if (cur != null) {
                    throw new IllegalStateException("Callback implements both " + cur + " and " + info.cls);
                }
                // 将info的class赋值给cur
                cur = info.cls;
                // 将info的type赋值给type
                type = info.type;
                // 如果不需要检查所有的类型，那么找到对应的类型后就跳出循环
                if (!checkAll) {
                    break;
                }
            }
        }
        // 如果最终没有找到对应的类型，报错
        if (cur == null) {
            throw new IllegalStateException("Unknown callback type " + callbackType);
        }
        // 返回type
        return type;
    }

    private static CallbackGenerator getGenerator(Type callbackType) {
        // 遍历CALLBACKS数组
        for (int i = 0; i < CALLBACKS.length; i++) {
            CallbackInfo info = CALLBACKS[i];
            // 如果发现对应的CallbackInfo的type和传入的type相等，那么返回info所持有的generator
            if (info.type.equals(callbackType)) {
                return info.generator;
            }
        }
        throw new IllegalStateException("Unknown callback type " + callbackType);
    }
}
    

