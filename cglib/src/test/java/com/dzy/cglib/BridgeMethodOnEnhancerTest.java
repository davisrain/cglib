package com.dzy.cglib;

import net.sf.cglib.core.Signature;
import net.sf.cglib.core.TypeUtils;
import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.CallbackFilter;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.Factory;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

public class BridgeMethodOnEnhancerTest {

    public static void main(String[] args) throws Exception {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Bar.class);
        enhancer.setCallbackTypes(new Class[] {BridgeMethodInterceptor.class, NormalMethodInterceptor.class});
        enhancer.setCallbackFilter(new CustomCallbackFilter());
        Class proxyClass = enhancer.createClass();
        Factory proxy = (Factory) proxyClass.newInstance();
        proxy.setCallbacks(new Callback[] {new BridgeMethodInterceptor(), new NormalMethodInterceptor()});

        Foo proxyFoo = (Foo) proxy;
        proxyFoo.publicMethod();
        proxyFoo.genericParameter("test");
        proxyFoo.covarianceReturnType();

        Bar proxyBar = (Bar) proxy;
        proxyBar.publicMethod();
        proxyBar.genericParameter("test");
        proxyBar.covarianceReturnType();
        // 尝试连续通过super关键字调用方法
        proxyBar.invokeSuper();
    }

    static class Foo<T> {

        public void publicMethod() {
            System.out.println("Foo.publicMethod");
        }

        public void genericParameter(T t) {
            System.out.println("Foo.genericParameter " + t);
        }

        public Object covarianceReturnType() {
            System.out.println("Foo.covarianceReturnType");
            return new Object();
        }

        public void invokeSuper() {
            System.out.println("Foo.invokeSuper");
        }
    }


    public static class Bar extends Foo<String> {
        // 这个类会有三个桥接方法
        // 1.扩展可见性的publicMethod方法
//        @Override
//        public void publicMethod() {
//            super.publicMethod();
//        }
        // 2.泛型擦除的重写方法
//        public void genericParameter(Object t) {
//            genericParameter((String) t);
//        }
        // 3.协变返回类型的重写方法
//        public Object covarianceReturnType() {
//            return covarianceReturnType();
//        }
        // 对于方法1，enhancer会跳过，重写的是Foo里面的publicMethod，该方法不是桥接方法，所以只会走到NormalMethodInterceptor

        // 对于方法2、3，enhancer会对这两个桥接方法进行代理，尝试将代理后的对象声明为Foo类型的变量，然后调用方法2、3，应该就会被分派到桥接方法上，会执行BridgeMethodInterceptor，可以检验桥接方法是否被代理；
        // 然后再调用MethodProxy的invokeSuper，调用到父类的同签名方法，即执行Bar里面方法的逻辑，调用被桥接方法，会执行NormalMethodInterceptor，可以验证被桥接的方法是否被代理

        // 然后将代理对象声明为Bar类型变量，调用方法2、3，此时不会应该不会被分派到桥接方法上面，即只会执行NormalMethodInterceptor
        @Override
        public void genericParameter(String s) {
            System.out.println("Bar.genericParameter " + s);
        }

        @Override
        public String covarianceReturnType() {
            System.out.println("Bar.covarianceReturnType");
            return "";
        }

        @Override
        public void invokeSuper() {
            System.out.println("Bar.invokeSuper");
            super.invokeSuper();
        }
    }

    static class CustomCallbackFilter implements CallbackFilter {

        @Override
        public int accept(Method method) {
            if (method.isBridge())
                return 0;
            return 1;
        }
    }

    static class BridgeMethodInterceptor implements MethodInterceptor {

        @Override
        public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
            Class<?>[] parameterTypes = method.getParameterTypes();
            System.out.println("bridge method invoked: " + method.getName() + ", signature: ");
            System.out.println(new Signature(method.getName(), Type.getType(method.getReturnType()), TypeUtils.getTypes(parameterTypes)));
            Object result = proxy.invokeSuper(obj, args);
            System.out.println();
            return result;
        }
    }

    static class NormalMethodInterceptor implements MethodInterceptor {

        @Override
        public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
            Class<?>[] parameterTypes = method.getParameterTypes();
            System.out.println("normal method invoked: " + method.getName() + ", signature: ");
            System.out.println(new Signature(method.getName(), Type.getType(method.getReturnType()), TypeUtils.getTypes(parameterTypes)));
            Object result = proxy.invokeSuper(obj, args);
            System.out.println();
            return result;
        }
    }
}
