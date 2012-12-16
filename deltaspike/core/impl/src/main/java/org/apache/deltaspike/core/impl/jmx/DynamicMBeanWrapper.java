/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.deltaspike.core.impl.jmx;

import org.apache.deltaspike.core.api.config.ConfigResolver;
import org.apache.deltaspike.core.api.jmx.annotation.JmxDescription;
import org.apache.deltaspike.core.api.jmx.annotation.JmxManaged;
import org.apache.deltaspike.core.api.jmx.annotation.NotificationInfo;
import org.apache.deltaspike.core.api.provider.BeanManagerProvider;
import org.apache.deltaspike.core.api.provider.BeanProvider;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.ImmutableDescriptor;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.ReflectionException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DynamicMBeanWrapper implements DynamicMBean
{
    public static final Logger LOGGER = Logger.getLogger(DynamicMBeanWrapper.class.getName());

    private final MBeanInfo info;
    private final Map<String, FieldInfo> fields = new HashMap<String, FieldInfo>();
    private final Map<String, Method> operations = new HashMap<String, Method>();
    private final ClassLoader classloader;
    private final Class<?> clazz;
    private final boolean normalScope;

    private final Annotation[] qualifiers;

    private Object instance = null;

    public DynamicMBeanWrapper(final Class<?> annotatedMBean, final boolean normalScope, final Annotation[] qualifiers)
    {
        this.clazz = annotatedMBean;
        this.classloader = Thread.currentThread().getContextClassLoader();
        this.normalScope = normalScope;
        this.qualifiers = qualifiers;

        final List<MBeanAttributeInfo> attributeInfos = new ArrayList<MBeanAttributeInfo>();
        final List<MBeanOperationInfo> operationInfos = new ArrayList<MBeanOperationInfo>();
        final List<MBeanNotificationInfo> notificationInfos = new ArrayList<MBeanNotificationInfo>();

        // class
        final String description =
            getDescription(annotatedMBean.getAnnotation(JmxDescription.class), annotatedMBean.getName());

        final NotificationInfo notification = annotatedMBean.getAnnotation(NotificationInfo.class);
        if (notification != null)
        {
            notificationInfos.add(getNotificationInfo(notification, annotatedMBean.getName()));
        }

        final NotificationInfo.List notifications = annotatedMBean.getAnnotation(NotificationInfo.List.class);
        if (notifications != null)
        {
            for (NotificationInfo notificationInfo : notifications.value())
            {
                notificationInfos.add(getNotificationInfo(notificationInfo, annotatedMBean.getName()));
            }
        }


        // methods
        for (Method method : annotatedMBean.getMethods())
        {
            final int modifiers = method.getModifiers();
            if (method.getDeclaringClass().equals(Object.class)
                    || !Modifier.isPublic(modifiers)
                    || Modifier.isAbstract(modifiers)
                    || Modifier.isStatic(modifiers)
                    || method.getAnnotation(JmxManaged.class) == null)
            {
                continue;
            }

            operations.put(method.getName(), method);

            String operationDescr = getDescription(method.getAnnotation(JmxDescription.class),
                annotatedMBean.getName() + "#" + method.getName());

            operationInfos.add(new MBeanOperationInfo(operationDescr, method));
        }

        Class<?> clazz = annotatedMBean;
        while (!Object.class.equals(clazz) && clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                final JmxManaged annotation = field.getAnnotation(JmxManaged.class);
                if (annotation != null) {
                    field.setAccessible(true);

                    final String name = field.getName();
                    final String fieldDescription = getDescription(field.getAnnotation(JmxDescription.class),
                            annotatedMBean.getClass() + "#" + name);
                    final Class<?> type = field.getType();

                    final String javaMtdName;
                    if (name.length() > 1) {
                        javaMtdName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    } else {
                        javaMtdName = "" + Character.toUpperCase(name.charAt(0));
                    }

                    Method setter = null;
                    Method getter = null;
                    try {
                        getter = clazz.getMethod("get" + javaMtdName);
                    } catch (NoSuchMethodException e1) {
                        try { // since we handle it ourself we treat it as a normal getter
                            getter = clazz.getMethod("is" + javaMtdName);
                        } catch (NoSuchMethodException e2) {
                            // ignored
                        }
                    }
                    try {
                        setter = clazz.getMethod("set" + javaMtdName, field.getType());
                    } catch (NoSuchMethodException e) {
                        // ignored
                    }

                    attributeInfos.add(new MBeanAttributeInfo(name, type.getName(),
                            fieldDescription, getter != null, setter != null, false));

                    fields.put(name, new FieldInfo(field, getter, setter));
                }
            }
            clazz = clazz.getSuperclass();
        }

        info = new MBeanInfo(annotatedMBean.getName(),
                description,
                attributeInfos.toArray(new MBeanAttributeInfo[attributeInfos.size()]),
                null, // default constructor is mandatory
                operationInfos.toArray(new MBeanOperationInfo[operationInfos.size()]),
                notificationInfos.toArray(new MBeanNotificationInfo[notificationInfos.size()]));
    }

    private MBeanNotificationInfo getNotificationInfo(final NotificationInfo n, String sourceInfo)
    {
        return new MBeanNotificationInfo(n.types(),
                n.notificationClass().getName(), getDescription(n.description(), sourceInfo),
                new ImmutableDescriptor(n.descriptorFields()));
    }

    private String getDescription(final JmxDescription description, String defaultDescription)
    {
        if (description == null || "".equals(description.value()))
        {
            return defaultDescription;
        }

        String descriptionValue = description.value().trim();

        if (descriptionValue.startsWith("{") && descriptionValue.endsWith("}"))
        {

            if (description.annotationType().getEnclosingMethod() != null)
            {
                defaultDescription = description.annotationType().getEnclosingMethod().getName();
            }
            else if (description.annotationType().getEnclosingClass() != null)
            {
                defaultDescription = description.annotationType().getEnclosingClass().getName();
            }
            return ConfigResolver.getPropertyValue(
                descriptionValue.substring(1, descriptionValue.length() - 1), defaultDescription);
        }
        return description.value();
    }

    @Override
    public MBeanInfo getMBeanInfo()
    {
        return info;
    }

    @Override
    public Object getAttribute(final String attribute)
        throws AttributeNotFoundException, MBeanException, ReflectionException
    {
        if (fields.containsKey(attribute))
        {
            final ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(classloader);
            try
            {
                return fields.get(attribute).get(instance());
            }
            catch (IllegalArgumentException e)
            {
                LOGGER.log(Level.SEVERE, "can't get " + attribute + " value", e);
            }
            catch (IllegalAccessException e)
            {
                LOGGER.log(Level.SEVERE, "can't get " + attribute + " value", e);
            }
            catch (InvocationTargetException e)
            {
                LOGGER.log(Level.SEVERE, "can't get " + attribute + " value", e);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(oldCl);
            }
        }
        throw new AttributeNotFoundException();
    }

    @Override
    public void setAttribute(final Attribute attribute)
        throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException
    {
        if (fields.containsKey(attribute.getName()))
        {
            final ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(classloader);
            try
            {
                fields.get(attribute.getName()).set(instance(), attribute.getValue());
            }
            catch (IllegalArgumentException e)
            {
                LOGGER.log(Level.SEVERE, "can't set " + attribute + " value", e);
            }
            catch (IllegalAccessException e)
            {
                LOGGER.log(Level.SEVERE, "can't set " + attribute + " value", e);
            }
            catch (InvocationTargetException e)
            {
                LOGGER.log(Level.SEVERE, "can't set " + attribute + " value", e);
            } finally
            {
                Thread.currentThread().setContextClassLoader(oldCl);
            }
        }
        else
        {
            throw new AttributeNotFoundException();
        }
    }

    @Override
    public AttributeList getAttributes(final String[] attributes)
    {
        final AttributeList list = new AttributeList();
        for (String n : attributes)
        {
            try
            {
                list.add(new Attribute(n, getAttribute(n)));
            }
            catch (Exception ignore)
            {
                // no-op
            }
        }
        return list;
    }

    @Override
    public AttributeList setAttributes(final AttributeList attributes)
    {
        final AttributeList list = new AttributeList();
        for (Object o : attributes)
        {
            final Attribute attr = (Attribute) o;
            try
            {
                setAttribute(attr);
                list.add(attr);
            }
            catch (Exception ignore)
            {
                // no-op
            }
        }
        return list;
    }

    @Override
    public Object invoke(final String actionName, final Object[] params, final String[] signature)
        throws MBeanException, ReflectionException
    {
        if (operations.containsKey(actionName))
        {
            final ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(classloader);
            try
            {
                return operations.get(actionName).invoke(instance(), params);
            }
            catch (IllegalArgumentException e)
            {
                LOGGER.log(Level.SEVERE, actionName + "can't be invoked", e);
            }
            catch (IllegalAccessException e)
            {
                LOGGER.log(Level.SEVERE, actionName + "can't be invoked", e);
            }
            catch (InvocationTargetException e)
            {
                LOGGER.log(Level.SEVERE, actionName + "can't be invoked", e);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(oldCl);
            }
        }
        throw new MBeanException(new IllegalArgumentException(), actionName + " doesn't exist");
    }

    private synchronized Object instance()
    {
        final ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classloader);
        try
        {
            if (instance != null)
            {
                return instance;
            }

            if (normalScope)
            {
                instance = BeanProvider.getContextualReference(clazz, qualifiers);
            }
            else
            {
                final BeanManager bm = BeanManagerProvider.getInstance().getBeanManager();
                final Set<Bean<?>> beans = bm.getBeans(clazz, qualifiers);
                if (beans == null || beans.isEmpty())
                {
                    throw new IllegalStateException("Could not find beans for Type=" + clazz
                            + " and qualifiers:" + Arrays.toString(qualifiers));
                }

                final Bean<?> resolvedBean = bm.resolve(beans);
                final CreationalContext<?> creationalContext = bm.createCreationalContext(resolvedBean);
                instance = bm.getReference(resolvedBean, clazz, creationalContext);
                creationalContext.release();
            }
            return instance;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldCl);
        }
    }
}
