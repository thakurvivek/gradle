/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.classpath;

import org.gradle.util.GUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

public class DefaultPluginModuleRegistry implements PluginModuleRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPluginModuleRegistry.class);
    private final ModuleRegistry moduleRegistry;

    public DefaultPluginModuleRegistry(ModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    public Set<Module> getPluginModules() {
        Set<Module> modules = new LinkedHashSet<Module>();
        Properties properties = loadPluginProperties();
        for (String pluginModule : properties.getProperty("plugins").split(",")) {
            try {
                modules.add(moduleRegistry.getModule(pluginModule));
            } catch (UnknownModuleException e) {
                // Ignore
                LOGGER.debug("Cannot find module for plugin {}. Ignoring.", pluginModule);
            }
        }
        return modules;
    }

    private Properties loadPluginProperties() {
        return GUtil.loadProperties(getClass().getResource("/gradle-plugins.properties"));
    }
}
