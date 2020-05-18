/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  public void applyIncludes(Node source) {
    Properties variablesContext = new Properties();
    Properties configurationVariables = configuration.getVariables();
    // 将 configurationVariables 中的数据添加到 variablesContext 中
    Optional.ofNullable(configurationVariables).ifPresent(variablesContext::putAll);
    // 调用重载方法处理 <include> 节点
    applyIncludes(source, variablesContext, false);
  }

  /**
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    if (source.getNodeName().equals("include")) {
      /*
       * 获取 <sql> 节点。若 refid 中包含属性占位符 ${}，
       * 则需先将属性占位符替换为对应的属性值
       */
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
      /*
       * 解析 <include> 的子节点 <property>，并将解析结果与 variablesContext 融合，
       * 然后返回融合后的 Properties。若 <property> 节点的 value 属性中存在占位符 ${}，
       * 则将占位符替换为对应的属性值
       */
      Properties toIncludeContext = getVariablesContext(source, variablesContext);
      /*
       * 这里是一个递归调用，用于将 <sql> 节点内容中出现的属性占位符 ${} 替换为对应的
       * 属性值。这里要注意一下递归调用的参数：
       *
       *  - toInclude：<sql> 节点对象
       *  - toIncludeContext：<include> 子节点 <property> 的解析结果与
       *                      全局变量融合后的结果
       */
      applyIncludes(toInclude, toIncludeContext, true);
      /*
       * 如果 <sql> 和 <include> 节点不在一个文档中，
       * 则从其他文档中将 <sql> 节点引入到 <include> 所在文档中
       */
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      // 将 <include> 节点替换为 <sql> 节点
      source.getParentNode().replaceChild(toInclude, source);
      while (toInclude.hasChildNodes()) {
        // 将 <sql> 中的内容插入到 <sql> 节点之前
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      /*
       * 前面已经将 <sql> 节点的内容插入到 dom 中了，
       * 现在不需要 <sql> 节点了，这里将该节点从 dom 中移除
       */
      toInclude.getParentNode().removeChild(toInclude);
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          // 将 source 节点属性中的占位符 ${} 替换成具体的属性值
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        // 递归调用
        applyIncludes(children.item(i), variablesContext, included);
      }
    } else if (included && (source.getNodeType() == Node.TEXT_NODE || source.getNodeType() == Node.CDATA_SECTION_NODE)
        && !variablesContext.isEmpty()) {
      // replace variables in text node
      // 将文本（text）节点中的属性占位符 ${} 替换成具体的属性值
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  private Node findSqlFragment(String refid, Properties variables) {
    refid = PropertyParser.parse(refid, variables);
    // 如果配置的refid包含.这说明已经指定了namespace
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      // 从全局配置中获取该id对应的 sql片段
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * Read placeholders and their values from include node definition.
   * @param node Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    Map<String, String> declaredProperties = null;
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        String name = getStringAttribute(n, "name");
        // Replace variables inside
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          declaredProperties = new HashMap<>();
        }
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
