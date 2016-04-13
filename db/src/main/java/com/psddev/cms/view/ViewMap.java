package com.psddev.cms.view;

import com.psddev.dari.db.Recordable;
import com.psddev.dari.db.State;
import com.psddev.dari.util.Once;
import com.psddev.dari.util.Settings;
import com.psddev.dari.util.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.IntrospectionException;
import java.beans.Introspector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Am unmodifiable Map implementation that uses a view (java bean) as the
 * backing object for the keys and values within the map. This Map uses the
 * bean spec to map the getter methods of the backing view to the keys within
 * the map.
 */
class ViewMap implements Map<String, Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ViewMap.class);

    private Map<String, Supplier<Object>> unresolved;

    private Map<String, Object> resolved;

    private boolean includeClassName;

    private Object view;

    private Once resolver = new Once() {
        @Override
        protected void run() throws Exception {
            // copy keys to new set to prevent concurrent modification exception.
            new LinkedHashSet<>(ViewMap.this.unresolved.keySet()).forEach(ViewMap.this::get);
        }
    };

    /**
     * Creates a new Map backed by the specified view.
     *
     * @param view the view to wrap.
     */
    public ViewMap(Object view) {
        this(view, false);
    }

    /**
     * Creates a new Map backed by the specified view.
     *
     * @param view the view to wrap.
     * @param includeClassName true if class names for each view should be included in the map.
     */
    public ViewMap(Object view, boolean includeClassName) {
        this.includeClassName = includeClassName;
        this.view = view;
        this.unresolved = new LinkedHashMap<>();
        this.resolved = new LinkedHashMap<>();

        // find all the classes that should be checked for bean properties
        getViewClasses(view)
                .stream()
                // grab the list of bean property descriptors
                .map(ViewMap::getBeanPropertyDescriptors)
                // flatten the descriptors across all the interfaces
                .flatMap(Collection::stream)
                // exclude the getClass() method
                .filter((prop) -> !"class".equals(prop.getName()))
                // ensure the read (getter) method is present
                .filter((prop) -> prop.getReadMethod() != null)
                // load the properties into a map.
                .collect(Collectors.toMap(
                        // key is the descriptor name
                        PropertyDescriptor::getName,
                        // value is a supplier of the read method's value.
                        prop -> () -> invoke(prop.getReadMethod(), view),
                        // merge function just keeps the original value
                        (m1, m2) -> m1,
                        // store them in the unresolved map
                        () -> unresolved));

        if (includeClassName) {
            resolved.put("class", view.getClass().getName());
        }
    }

    private static List<Class<?>> getViewClasses(Object view) {

        // find all the classes that could contain annotations
        List<Class<?>> viewInterfaces = ViewUtils.getAnnotatableClasses(view.getClass())
                .stream()
                // only look at interfaces
                .filter(Class::isInterface)
                // that are annotated with @ViewInterface
                .filter(klass -> klass.isAnnotationPresent(ViewInterface.class))
                // add to list
                .collect(Collectors.toList());

        // if there are none, just return a single item list with the original class
        // TODO: Eventually this can be removed, and @ViewInterface will be required.
        if (viewInterfaces.isEmpty()) {
            viewInterfaces = Collections.singletonList(view.getClass());
        }

        return viewInterfaces;
    }

    private static List<PropertyDescriptor> getBeanPropertyDescriptors(Class<?> viewClass) {
        try {
            return Arrays.asList(Introspector.getBeanInfo(viewClass).getPropertyDescriptors());
        } catch (IntrospectionException e) {
            LOGGER.warn("Failed to introspect bean info for view of type ["
                    + viewClass.getClass().getName() + "]. Cause: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * @return the backing view object for this map.
     */
    public Object getView() {
        return view;
    }

    @Override
    public int size() {
        resolver.ensure();
        return resolved.size();
    }

    @Override
    public boolean isEmpty() {
        resolver.ensure();
        return resolved.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        resolver.ensure();
        return resolved.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        resolver.ensure();
        return resolved.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        if (key instanceof String) {

            if (resolved.containsKey(key)) {
                return resolved.get(key);

            } else {
                Supplier<Object> supplier = unresolved.remove(key);

                if (supplier != null) {
                    Object value = convertValue(supplier.get());
                    if (value != null) {
                        resolved.put((String) key, value);
                    }
                    return value;

                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends String, ?> map) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> keySet() {
        resolver.ensure();
        return Collections.unmodifiableSet(resolved.keySet());
    }

    @Override
    public Collection<Object> values() {
        resolver.ensure();
        return Collections.unmodifiableCollection(resolved.values());
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        resolver.ensure();
        return Collections.unmodifiableSet(resolved.entrySet());
    }

    @Override
    public String toString() {

        return "{" + StringUtils.join(entrySet()
                .stream()
                .map((e) -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.toList()), ", ") + "}";
    }

    /*
     * Converts a value to a Json Map friendly value. Only supports String,
     * Boolean, Number, Collection, and simple String key (non-State) based Maps.
     */
    private Object convertValue(Object value) {

        // FIXME: Always exclude database objects for now. Eventually
        // @ViewInterface will be required, naturally excluding these object types
        if (value instanceof State || value instanceof Recordable) {
            return null;
        }

        if (value instanceof String) {
            return value;

        } else if (value instanceof Boolean) {
            return value;

        } else if (value instanceof Number) {
            return value;

        } else if (value instanceof Collection) {
            List<Object> immutableViewList = new ArrayList<>();

            for (Object item : (Iterable<?>) value) {

                Object convertedItem = convertValue(item);
                if (convertedItem != null) {
                    immutableViewList.add(convertedItem);
                }
            }
            return immutableViewList;

        } else if (value instanceof ViewMap) { // pass through
            return value;

        } else if (value instanceof Map) {

            Map<String, Object> convertedMap = new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object entryKey = entry.getKey();
                Object entryValue = entry.getValue();

                if (entryKey instanceof String) {
                    Object convertedEntryValue = convertValue(entryValue);

                    if (convertedEntryValue != null) {
                        convertedMap.put((String) entryKey, convertedEntryValue);
                    }
                }
            }

            return convertedMap;

        } else if (value != null) {
            return new ViewMap(value, includeClassName);
        }

        return null;
    }

    private static Object invoke(Method method, Object view) {
        try {
            method.setAccessible(true);
            return method.invoke(view);

        } catch (IllegalAccessException | InvocationTargetException e) {

            String message = "Failed to invoke method: " + method;

            Throwable cause = e.getCause();
            cause = cause != null ? cause : e;

            ViewResponse response = ViewResponse.findInExceptionChain(cause);
            if (response != null) {
                throw response;
            }

            LOGGER.error(message, cause);

            if (Settings.isProduction()) {
                return null;
            } else {
                throw new RuntimeException(message, cause);
            }
        }
    }
}
