package net.sf.cglib.core.internal;

import net.sf.cglib.core.Customizer;
import net.sf.cglib.core.FieldTypeCustomizer;
import net.sf.cglib.core.KeyFactoryCustomizer;

import java.util.*;

public class CustomizerRegistry {
    private final Class[] customizerTypes;
    private Map<Class, List<KeyFactoryCustomizer>> customizers = new HashMap<Class, List<KeyFactoryCustomizer>>();

    public CustomizerRegistry(Class[] customizerTypes) {
        this.customizerTypes = customizerTypes;
    }

    public void add(KeyFactoryCustomizer customizer) {
        // 获取customizer的类型
        Class<? extends KeyFactoryCustomizer> klass = customizer.getClass();
        // 如果是registry持有的customizerTypes中任意一种
        for (Class type : customizerTypes) {
            if (type.isAssignableFrom(klass)) {
                // 那么添加到customizer这个map中的对应的key的list中去
                List<KeyFactoryCustomizer> list = customizers.get(type);
                if (list == null) {
                    customizers.put(type, list = new ArrayList<KeyFactoryCustomizer>());
                }
                list.add(customizer);
            }
        }
    }

    public <T> List<T> get(Class<T> klass) {
        List<KeyFactoryCustomizer> list = customizers.get(klass);
        if (list == null) {
            return Collections.emptyList();
        }
        return (List<T>) list;
    }
    
    /**
     * @deprecated Only to keep backward compatibility.
     */
    @Deprecated
    public static CustomizerRegistry singleton(Customizer customizer)
    {
        CustomizerRegistry registry = new CustomizerRegistry(new Class[]{Customizer.class});
        registry.add(customizer);
        return registry;
    }
}
