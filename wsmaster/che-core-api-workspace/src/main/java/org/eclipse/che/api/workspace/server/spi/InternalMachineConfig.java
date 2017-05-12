/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.api.workspace.server.spi;

import org.eclipse.che.api.agent.server.AgentRegistry;
import org.eclipse.che.api.agent.server.exception.AgentException;
import org.eclipse.che.api.agent.server.impl.AgentSorter;
import org.eclipse.che.api.agent.shared.model.Agent;
import org.eclipse.che.api.agent.shared.model.AgentKey;
import org.eclipse.che.api.core.model.workspace.config.MachineConfig;
import org.eclipse.che.api.core.model.workspace.config.ServerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.String.format;

/**
 * "pre-processed" Machine Config. To use inside infrastructure
 *
 * @author gazarenkov
 */
public class InternalMachineConfig {

    // ordered agent scripts to launch on start
    private final List<ResolvedAgent>       agents;
    // set of servers including ones configured by agents
    private final Map<String, ServerConfig> servers;
    private final Map<String, String>       attributes;

    private final AgentSorter   agentSorter;
    private final AgentRegistry agentRegistry;

    public InternalMachineConfig(MachineConfig originalConfig,
                                 AgentRegistry agentRegistry,
                                 AgentSorter agentSorter) throws InfrastructureException {
        this.agentSorter = agentSorter;
        this.agentRegistry = agentRegistry;
        this.agents = new ArrayList<>();
        this.servers = new HashMap<>();
        this.servers.putAll(originalConfig.getServers());
        this.attributes = new HashMap<>(originalConfig.getAttributes());

        initAgents(originalConfig.getAgents());
    }

    /**
     * @return servers
     */
    public Map<String, ServerConfig> getServers() {
        return Collections.unmodifiableMap(servers);
    }

    /**
     * @return agent scripts
     */
    public List<ResolvedAgent> getAgents() {
        return agents;
    }

    /**
     * @return attributes
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    private void initAgents(List<String> agentIds) throws InfrastructureException {
        try {
            // TODO ensure already contains dependencies
            List<AgentKey> sortedAgents = agentSorter.sort(agentIds);
            List<Agent> agentsConf = new ArrayList<>();
            for (AgentKey agentKey : sortedAgents) {
                agentsConf.add(agentRegistry.getAgent(agentKey));
            }
            for (Agent agent : agentsConf) {
                this.agents.add(new ResolvedAgent(agent.getId(),
                                                  agent.getScript(),
                                                  agent.getServers().keySet(),
                                                  agent.getProperties()));
                for (Map.Entry<String, ? extends ServerConfig> serverEntry : agent.getServers().entrySet()) {
                    if (servers.putIfAbsent(serverEntry.getKey(), serverEntry.getValue()) != null &&
                        servers.get(serverEntry.getKey()).equals(serverEntry.getValue())) {
                        throw new InfrastructureException(
                                format("Agent '%s' contains server '%s' conflicting with machine configuration",
                                       agent.getId(), serverEntry.getKey()));
                    }
                }
            }
        } catch (AgentException e) {
            // TODO agents has circular dependency or missing, what should we throw in that case?
            throw new InfrastructureException(e.getLocalizedMessage(), e);
        }
    }

    public static class ResolvedAgent {
        private String              id;
        private String              script;
        // needed to know which servers should be pinged on start of machine
        private Set<String>         serversRefs;
        private Map<String, String> properties;

        public ResolvedAgent(String id,
                             String script,
                             Set<String> servers,
                             Map<String, String> properties) {
            this.id = id;
            this.script = script;
            this.serversRefs = servers;
            this.properties = properties;
        }

        public String getId() {
            return id;
        }

        public String getScript() {
            return script;
        }

        public Set<String> getServers() {
            return serversRefs;
        }

        public Map<String, String> getProperties() {
            return properties;
        }
    }
}