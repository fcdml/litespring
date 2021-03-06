package org.deppwang.litespring.v3;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IoC 容器，此时本质上是一个 ConcurrentHashMap，key 为 beanId，value 为 Bean
 * 跟 XmlBeanFactory 的区别在于提前将所用 Bean 注入容器
 */
public class ClassPathXmlApplicationContext implements BeanFactory {

    private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(64);

    // 使用 ConcurrentHashMap 存放所有单例 Bean，String 为 beanId（beanName）
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(64);

    public ClassPathXmlApplicationContext(String configFile) {
        loadBeanDefinitions(configFile);
        prepareBeanRegister();
    }


    private void loadBeanDefinitions(String configFile) {
        InputStream is = null;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();

            is = cl.getResourceAsStream(configFile); // 根据 configFile 获取 petstore-v1.xml 文件的字节流

            SAXReader reader = new SAXReader();
            Document doc = reader.read(is); // 将字节流转成文档格式

            Element root = doc.getRootElement(); // <beans>
            Iterator iter = root.elementIterator();
            // 遍历所有 bean
            while (iter.hasNext()) {
                Element ele = (Element) iter.next();
                String id = ele.attributeValue("id");
                String beanClassName = ele.attributeValue("class");
                BeanDefinition bd = new BeanDefinition(id, beanClassName);
                parseConstructorArgElement(ele, bd);
                parsePropertyElement(ele, bd);
                this.beanDefinitionMap.put(id, bd);
            }
        } catch (DocumentException e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void parseConstructorArgElement(Element beanElem, BeanDefinition bd) {
        Iterator iter = beanElem.elementIterator("constructor-arg");
        while (iter.hasNext()) {
            Element propElem = (Element) iter.next();
            String argumentName = propElem.attributeValue("ref");
            if (!StringUtils.hasLength(argumentName)) {
                return;
            }

            bd.getConstructorArgumentValues().add(argumentName);
        }

    }


    private void parsePropertyElement(Element beanElem, BeanDefinition bd) {
        Iterator iter = beanElem.elementIterator("property");
        while (iter.hasNext()) {
            Element propElem = (Element) iter.next();
            String propertyName = propElem.attributeValue("name");
            if (!StringUtils.hasLength(propertyName)) {
                return;
            }

            bd.getPropertyNames().add(propertyName);
        }

    }

    /**
     * ApplicationContext 特点，第一次加载即注入所有 bean 到容器
     */
    private void prepareBeanRegister() {
        for (String beanId : beanDefinitionMap.keySet()) {
            this.getBean(beanId);
        }
    }

    /**
     * 根据 beanId 获取实例，没有则生成
     *
     * @param beanId
     * @return
     */
    public Object getBean(String beanId) {
        BeanDefinition bd = this.getBeanDefinition(beanId);
        // 单例模式，一个类对应一个 Bean，不是通过 id。常规单例模式是多次调用方法，只生成一个实例。此处是只会调用依次生成实例方法。
        Object bean = this.getSingleton(beanId);
        if (bean == null) {
            bean = createBean(bd);
            this.registerSingleton(beanId, bean);
        }
        return bean;
    }


    public BeanDefinition getBeanDefinition(String beanID) {
        return this.beanDefinitionMap.get(beanID);
    }

    /**
     * 将单例 Bean 存放到 singletonObjects 中
     *
     * @param beanName
     * @param singletonObject
     */
    public void registerSingleton(String beanName, Object singletonObject) {
        Object oldObject = this.singletonObjects.get(beanName);
        if (oldObject != null) {
            System.out.println("error," + oldObject + "had already registered");
        }
        this.singletonObjects.put(beanName, singletonObject);
    }

    /**
     * 根据 beanName，从 singletonObjects 中获取到实例
     *
     * @param beanName
     * @return
     */
    public Object getSingleton(String beanName) {

        return this.singletonObjects.get(beanName);
    }

    private Object createBean(BeanDefinition bd) {
        // 创建实例
        Object bean = instantiateBean(bd);
        // 填充属性（依赖注入）
        populateBean(bd, bean);

        return bean;
    }

    private Object instantiateBean(BeanDefinition bd) {
        if (bd.hasConstructorArgumentValues()) {
            return autowireConstructor(bd);
        } else {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            String beanClassName = bd.getBeanClassName();
            try {
                Class<?> clz = cl.loadClass(beanClassName);
                return clz.newInstance();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    private Object autowireConstructor(final BeanDefinition bd) {
        Constructor<?> constructorToUse = null; // 代表最终匹配的 Constructor 对象
        Object[] argsToUse = null; // 代表将依赖注入的对象
        try {
            Class<?> beanClass = Thread.currentThread().getContextClassLoader().loadClass(bd.getBeanClassName());
            // 通过反射获取当前类的构造方法信息（Constructor 对象）
            Constructor<?>[] candidates = beanClass.getDeclaredConstructors();
            for (int i = 0; i < candidates.length; i++) {

                Class<?>[] parameterTypes = candidates[i].getParameterTypes();
                if (parameterTypes.length != bd.getConstructorArgumentValues().size()) {
                    continue;
                }
                argsToUse = new Object[parameterTypes.length];
                valuesMatchTypes(bd.getConstructorArgumentValues(), argsToUse);
                constructorToUse = candidates[i];
                break;
            }

            return constructorToUse.newInstance(argsToUse);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void valuesMatchTypes(
            List<String> beanNames,
            Object[] argsToUse) {
        for (int i = 0; i < beanNames.size(); i++) {
            Object argumentBean = getBean(beanNames.get(i));
            argsToUse[i] = argumentBean;
        }
    }

    private void populateBean(BeanDefinition bd, Object bean) {
        List<String> propertyNames = bd.getPropertyNames();
        try {
            Method[] methods = bean.getClass().getDeclaredMethods();
            for (String propertyName : propertyNames) {
                for (Method method : methods) {
                    if (method.getName().equals("set" + upperCaseFirstChar(propertyName))) {
                        Object propertyBean = getBean(propertyName);
                        method.invoke(bean, propertyBean);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String upperCaseFirstChar(String str) {
        char chars[] = str.toCharArray();
        chars[0] = Character.toUpperCase(chars[0]);
        return new String(chars);
    }
}
