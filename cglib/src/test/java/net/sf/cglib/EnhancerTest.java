package net.sf.cglib;

import net.sf.cglib.core.DefaultNamingPolicy;
import net.sf.cglib.proxy.*;

import java.lang.reflect.Method;

public class EnhancerTest {

    public static void main(String[] args) throws Exception {
        System.setProperty("cglib.debugLocation", EnhancerTest.class.getResource("").getPath());
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Dog.class);
        enhancer.setNamingPolicy(new CustomNamingPolicy());
        enhancer.setCallbackFilter(new CustomCallbackFilter());
        enhancer.setCallbackTypes(new Class[] {CustomMethodInterceptor.class, CountryInfoDispatcher.class, CustomFixedValue.class, NoOp.class});
        Class proxyClass = enhancer.createClass();
        Factory proxyFactory = (Factory) proxyClass.newInstance();
        Dog proxy = (Dog) proxyFactory.newInstance(new Callback[]{new CustomMethodInterceptor(), new CountryInfoDispatcher(), new CustomFixedValue(), NoOp.INSTANCE});
        proxy.eat();
        proxy.sleep();
        System.out.println(proxy.getCountry());
        System.out.println(proxy.getFixedValue());
        System.out.println(proxy.doNothing());
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
            return new ChineseDog();
        }
    }

    static class CustomFixedValue implements FixedValue {

        @Override
        public Object loadObject() throws Exception {
            return "FixedValue";
        }
    }


    static class Dog implements CountryInfo {

        public void eat() {
            System.out.println("dog is eating");
        }

        public void sleep() {
            System.out.println("dog is sleeping");
        }

        @Override
        public String getCountry() {
            return "Earth";
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

    static class ChineseDog extends Dog implements CountryInfo {
        @Override
        public String getCountry() {
            return "China";
        }
    }
}
