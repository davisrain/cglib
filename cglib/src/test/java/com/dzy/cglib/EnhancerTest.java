package com.dzy.cglib;

import net.sf.cglib.core.DefaultNamingPolicy;
import net.sf.cglib.proxy.*;

import java.lang.reflect.Method;

public class EnhancerTest {

    public static void main(String[] args) throws Exception {
        Callback[] callbacks = {new CustomMethodInterceptor(), new CountryInfoDispatcher(), new CustomFixedValue(), NoOp.INSTANCE, new ProvinceInfoLazyLoader()};
        System.setProperty("cglib.debugLocation", EnhancerTest.class.getResource("").getPath());
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Dog.class);
        enhancer.setInterfaces(new Class[]{CountryInfo.class, ProvinceInfo.class});
        enhancer.setNamingPolicy(new CustomNamingPolicy());
        enhancer.setCallbackFilter(new CustomCallbackFilter());
        enhancer.setCallbackTypes(new Class[] {CustomMethodInterceptor.class, CountryInfoDispatcher.class, CustomFixedValue.class, NoOp.class, ProvinceInfoLazyLoader.class});
        Class proxyClass = enhancer.createClass();
        Factory proxyFactory = (Factory) proxyClass.newInstance();

        // 1.直接在factory里面设置callbacks，把它当作代理对象来使用
        proxyFactory.setCallbacks(callbacks);
        System.out.println(((ProvinceInfo) proxyFactory).getProvince());

        // 2.根据callbacks创建一个新的代理对象出来
        Dog proxy = (Dog) proxyFactory.newInstance(callbacks);
        System.out.println(proxy.toString());
        proxy.eat();
        proxy.sleep();
        System.out.println(((CountryInfo) proxy).getCountry());
        System.out.println(proxy.getFixedValue());
        System.out.println(proxy.doNothing());
        System.out.println(((ProvinceInfo) proxy).getProvince());
    }

    static class CustomNamingPolicy extends DefaultNamingPolicy {
        @Override
        protected String getTag() {
            return "byCustomCGLIB";
        }
    }

    static class CustomCallbackFilter implements CallbackFilter {

        @Override
        public int accept(Method method) {
            String methodName = method.getName();
            if (methodName.equals("getCountry"))
                return 1;
            else if (methodName.equals("getFixedValue"))
                return 2;
            else if (methodName.equals("doNothing"))
                return 3;
            else if (methodName.equals("getProvince"))
                return 4;
            return 0;
        }
    }

    static class CustomMethodInterceptor implements MethodInterceptor {

        @Override
        public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
            System.out.println("before method:" + method.getName());
            Object result = proxy.invokeSuper(obj, args);
            System.out.println("after method:" + method.getName());
            return result;
        }
    }

    static class CountryInfoDispatcher implements Dispatcher {

        @Override
        public Object loadObject() throws Exception {
            return new Chinese();
        }
    }

    static class ProvinceInfoLazyLoader implements LazyLoader {

        @Override
        public Object loadObject() throws Exception {
            return new SiChuan();
        }
    }

    static class CustomFixedValue implements FixedValue {

        @Override
        public Object loadObject() throws Exception {
            return "FixedValue";
        }
    }


    static class Dog {

        public void eat() {
            System.out.println("dog is eating");
        }

        public void sleep() {
            System.out.println("dog is sleeping");
        }

        public String getFixedValue() {
            return "";
        }

        public String doNothing() {
            return "doNothing";
        }
    }

    interface CountryInfo {
        String getCountry();
    }

    interface ProvinceInfo {
        String getProvince();
    }

    static class Chinese implements CountryInfo {
        @Override
        public String getCountry() {
            return "China";
        }
    }

    static class SiChuan implements ProvinceInfo {
        @Override
        public String getProvince() {
            return "sichuan";
        }
    }
}
