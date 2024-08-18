package com.dzy.cglib;

import net.sf.cglib.proxy.*;

import java.lang.reflect.Method;

public class ProxyDefaultMethodTest {

    public static void main(String[] args) throws Exception {
        Enhancer enhancer = new Enhancer();
        enhancer.setCallbackTypes(new Class[] {CustomMethodInterceptor.class});
        enhancer.setSuperclass(Foo.class);
        // 不设置callbackFilter，默认使用ALL_ZERO
//        enhancer.setCallbackFilter();
        Factory proxyFactory = (Factory) enhancer.createClass().newInstance();
        Foo proxyFoo = (Foo) proxyFactory.newInstance(new Callback[]{new CustomMethodInterceptor()});
        // 调用默认方法
        proxyFoo.defaultMethod();
    }

    interface DefaultMethodInterface {
        default void defaultMethod() {
            System.out.println("default method");
        }
    }

    static class Foo implements DefaultMethodInterface {

        public void fooMethod() {
            System.out.println("foo method");
        }
    }

    private static class CustomMethodInterceptor implements MethodInterceptor {

        @Override
        public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
            System.out.println("before " + method.getName() + " method");
            Object returnVal = proxy.invokeSuper(obj, args);
            System.out.println("after " + method.getName() + " method");
            return returnVal;
        }
    }
}
