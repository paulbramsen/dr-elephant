package com.linkedin.drelephant.configurations.scheduler;

import org.apache.log4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class manages the scheduler configurations
 */
public class SchedulerConfiguration {
  private List<SchedulerConfigurationData> _schedulerConfDataList;

  public SchedulerConfiguration(Element configuration) {
    parseSchedulerConfiguration(configuration);
  }

  public List<SchedulerConfigurationData> getSchedulerConfigurationData() {
    return _schedulerConfDataList;
  }

  private void parseSchedulerConfiguration(Element configuration) {
    _schedulerConfDataList = new ArrayList<SchedulerConfigurationData>();

    NodeList nodes = configuration.getChildNodes();
    int n = 0;
    for (int i = 0; i < nodes.getLength(); i++) {
      // Each scheduler node
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        n++;
        Element schedulerNode = (Element) node;

        String className;
        Node classNameNode = schedulerNode.getElementsByTagName("classname").item(0);
        if (classNameNode == null) {
          throw new RuntimeException("No tag 'classname' in scheduler " + n);
        }
        className = classNameNode.getTextContent();
        if (className.equals("")) {
          throw new RuntimeException("Empty tag 'classname' in scheduler " + n);
        }

        String schedulerName;
        Node schedulerNameNode = schedulerNode.getElementsByTagName("name").item(0);
        if (schedulerNameNode == null) {
          throw new RuntimeException("No tag 'name' in scheduler " + n + " classname " + className);
        }
        schedulerName = schedulerNameNode.getTextContent();
        if (schedulerName.equals("")) {
          throw new RuntimeException("Empty tag 'name' in scheduler " + n + " classname " + className);
        }

        // Check if parameters are defined for the scheduler
        Map<String, String> paramsMap = new HashMap<String, String>();
        Node paramsNode = schedulerNode.getElementsByTagName("params").item(0);
        if (paramsNode != null) {
          NodeList paramsList = paramsNode.getChildNodes();
          for (int j = 0; j < paramsList.getLength(); j++) {
            Node paramNode = paramsList.item(j);
            if (paramNode != null && !paramsMap.containsKey(paramNode.getNodeName())) {
              paramsMap.put(paramNode.getNodeName(), paramNode.getTextContent());
            }
          }
        }

        SchedulerConfigurationData schedulerData = new SchedulerConfigurationData(schedulerName, className, paramsMap);
        _schedulerConfDataList.add(schedulerData);

      }
    }
  }

}
