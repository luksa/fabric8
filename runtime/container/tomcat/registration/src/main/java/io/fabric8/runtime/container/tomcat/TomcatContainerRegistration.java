/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.runtime.container.tomcat;

import static io.fabric8.zookeeper.ZkPath.CONFIG_CONTAINER;
import static io.fabric8.zookeeper.ZkPath.CONFIG_VERSIONS_CONTAINER;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_ADDRESS;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_ALIVE;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_BINDADDRESS;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_DOMAINS;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_GEOLOCATION;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_HTTP;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_IP;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_LOCAL_HOSTNAME;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_LOCAL_IP;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_PORT_MAX;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_PORT_MIN;
import static io.fabric8.zookeeper.ZkPath.CONTAINER_RESOLVER;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.createDefault;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getStringData;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getSubstitutedData;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.setData;
import io.fabric8.api.Container;
import io.fabric8.api.ContainerRegistration;
import io.fabric8.api.GeoLocationService;
import io.fabric8.api.PortService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.ZkDefs;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.internal.ImmutableContainerBuilder;
import io.fabric8.utils.HostUtils;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.bootstrap.BootstrapConfiguration;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.apache.catalina.Server;
import org.apache.catalina.connector.Connector;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
@Component(name = "io.fabric8.container.registration.tomcat", label = "Fabric8 Tomcat Container Registration", immediate = true, metatype = false)
@Service({ContainerRegistration.class, ConnectionStateListener.class})
public final class TomcatContainerRegistration extends AbstractComponent implements ContainerRegistration, ConnectionStateListener {

    private transient Logger LOGGER = LoggerFactory.getLogger(TomcatContainerRegistration.class);

    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final int DEFAULT_HTTPS_PORT = 8443;

    @Reference(referenceInterface = MBeanServer.class, bind = "bindMBeanServer", unbind = "unbindMBeanServer")
    private final ValidatingReference<MBeanServer> mbeanServer = new ValidatingReference<MBeanServer>();
    @Reference(referenceInterface = RuntimeProperties.class)
    private final ValidatingReference<RuntimeProperties> runtimeProperties = new ValidatingReference<RuntimeProperties>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = PortService.class)
    private final ValidatingReference<PortService> portService = new ValidatingReference<PortService>();
    @Reference(referenceInterface = GeoLocationService.class)
    private final ValidatingReference<GeoLocationService> geoLocationService = new ValidatingReference<GeoLocationService>();
    @Reference(referenceInterface = BootstrapConfiguration.class)
    private final ValidatingReference<BootstrapConfiguration> bootstrapConfiguration = new ValidatingReference<BootstrapConfiguration>();

    private final Set<Connector> httpConnectors = new LinkedHashSet<Connector>();
    private final Set<Connector> httpsConnectors = new LinkedHashSet<Connector>();

    private Server server;
    private String runtimeIdentity;
    private String ip;

    @Activate
    void activate() {
        activateInternal();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    private void activateInternal() {
        RuntimeProperties sysprops = runtimeProperties.get();
        runtimeIdentity = sysprops.getRuntimeIdentity();
        String version = sysprops.getProperty("fabric.version", ZkDefs.DEFAULT_VERSION);
        String profiles = sysprops.getProperty("fabric.profiles");
        try {
            server = getServer();
            if (profiles != null) {
                String versionNode = CONFIG_CONTAINER.getPath(runtimeIdentity);
                String profileNode = CONFIG_VERSIONS_CONTAINER.getPath(version, runtimeIdentity);
                ZooKeeperUtils.createDefault(curator.get(), versionNode, version);
                ZooKeeperUtils.createDefault(curator.get(), profileNode, profiles);
            }

            checkAlive();

            String domainsNode = CONTAINER_DOMAINS.getPath(runtimeIdentity);
            Stat stat = ZooKeeperUtils.exists(curator.get(), domainsNode);
            if (stat != null) {
                ZooKeeperUtils.deleteSafe(curator.get(), domainsNode);
            }

            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_BINDADDRESS.getPath(runtimeIdentity), bootstrapConfiguration.get().getBindAddress());
            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_RESOLVER.getPath(runtimeIdentity), getContainerResolutionPolicy(curator.get(), runtimeIdentity));
            ZooKeeperUtils.setData(curator.get(), CONTAINER_LOCAL_HOSTNAME.getPath(runtimeIdentity), HostUtils.getLocalHostName());
            ZooKeeperUtils.setData(curator.get(), CONTAINER_LOCAL_IP.getPath(runtimeIdentity), HostUtils.getLocalIp());
            ZooKeeperUtils.setData(curator.get(), CONTAINER_IP.getPath(runtimeIdentity), getContainerPointer(curator.get(), runtimeIdentity));
            //Check if there are addresses specified as system properties and use them if there is not an existing value in the registry.
            //Mostly usable for adding values when creating containers without an existing ensemble.
            for (String resolver : ZkDefs.VALID_RESOLVERS) {
                String address = String.valueOf(bootstrapConfiguration.get().getConfiguration().get(resolver));
                if (address != null && !address.isEmpty() && exists(curator.get(), CONTAINER_ADDRESS.getPath(runtimeIdentity, resolver)) == null) {
                    setData(curator.get(), CONTAINER_ADDRESS.getPath(runtimeIdentity, resolver), address);
                }
            }

            ip =  getSubstitutedData(curator.get(), getContainerPointer(curator.get(), runtimeIdentity));
            setData(curator.get(), CONTAINER_IP.getPath(runtimeIdentity), ip);

            createDefault(curator.get(), CONTAINER_GEOLOCATION.getPath(runtimeIdentity), geoLocationService.get().getGeoLocation());
            //Check if there are addresses specified as system properties and use them if there is not an existing value in the registry.
            //Mostly usable for adding values when creating containers without an existing ensemble.
            for (String resolver : ZkDefs.VALID_RESOLVERS) {
                String address = sysprops.getProperty(resolver);
                if (address != null && !address.isEmpty() && ZooKeeperUtils.exists(curator.get(), CONTAINER_ADDRESS.getPath(runtimeIdentity, resolver)) == null) {
                    ZooKeeperUtils.setData(curator.get(), CONTAINER_ADDRESS.getPath(runtimeIdentity, resolver), address);
                }
            }

            //We are creating a dummy container object, since this might be called before the actual container is ready.
            Container current = new ImmutableContainerBuilder().id(runtimeIdentity).ip(ip).build();
            //Read all tomcat connectors
            initializeConnectors();
            registerHttp(current);

            //Set the port range values
            String minimumPort = sysprops.getProperty(ZkDefs.MINIMUM_PORT);
            String maximumPort = sysprops.getProperty(ZkDefs.MAXIMUM_PORT);
            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_PORT_MIN.getPath(runtimeIdentity), minimumPort);
            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_PORT_MAX.getPath(runtimeIdentity), maximumPort);
        } catch (Exception e) {
            LOGGER.warn("Error updating Fabric Container information. This exception will be ignored.", e);
        }
    }

    @Override
    public void stateChanged(CuratorFramework client, ConnectionState newState) {
        if (isValid()) {
            switch (newState) {
                case CONNECTED:
                case RECONNECTED:
                    try {
                        checkAlive();
                    } catch (Exception ex) {
                        LOGGER.error("Error while checking/setting container status.");
                    }
                    break;
            }
        }
    }

    private void checkAlive() throws Exception {
        RuntimeProperties sysprops = runtimeProperties.get();
        String runtimeIdentity = sysprops.getRuntimeIdentity();
        String nodeAlive = CONTAINER_ALIVE.getPath(runtimeIdentity);
        Stat stat = ZooKeeperUtils.exists(curator.get(), nodeAlive);
        if (stat != null) {
            if (stat.getEphemeralOwner() != curator.get().getZookeeperClient().getZooKeeper().getSessionId()) {
                ZooKeeperUtils.delete(curator.get(), nodeAlive);
                ZooKeeperUtils.create(curator.get(), nodeAlive, CreateMode.EPHEMERAL);
            }
        } else {
            ZooKeeperUtils.create(curator.get(), nodeAlive, CreateMode.EPHEMERAL);
        }
    }

    private Server getServer() throws MalformedObjectNameException, AttributeNotFoundException, MBeanException, ReflectionException, InstanceNotFoundException {
        ObjectName name = new ObjectName("Catalina", "type", "Server");
        return (Server) mbeanServer.get().getAttribute(name, "managedResource");
    }

    private void initializeConnectors() {
        org.apache.catalina.Service[] services = server.findServices();
        for (org.apache.catalina.Service service : services) {
            for (Connector connector : service.findConnectors()) {
                if (connector.getScheme().equals("http")) {
                    httpConnectors.add(connector);
                } else if (connector.getScheme().equals("https")) {
                    httpsConnectors.add(connector);
                }
            }
        }
    }


    private void registerHttp(Container container) throws Exception {
        boolean httpEnabled = isHttpEnabled();
        boolean httpsEnabled = isHttpsEnabled();
        String protocol = httpsEnabled && !httpEnabled ? "https" : "http";
        int httpPort = httpsEnabled && !httpEnabled ? getHttpsPort() : getHttpPort();
        String httpUrl = getHttpUrl(protocol, container.getId(), httpPort);
        ZooKeeperUtils.setData(curator.get(), CONTAINER_HTTP.getPath(container.getId()), httpUrl);
    }

    private boolean isHttpEnabled() throws IOException {
        return !httpConnectors.isEmpty();
    }

    private boolean isHttpsEnabled() throws IOException {
        return !httpsConnectors.isEmpty();
    }

    private int getHttpPort() {
        return httpConnectors.isEmpty() ? DEFAULT_HTTP_PORT : httpConnectors.iterator().next().getPort();
    }

    private String getHttpUrl(String protocol, String name, int httpConnectionPort) throws IOException, KeeperException, InterruptedException {
        return protocol + "://${zk:" + name + "/ip}:" + httpConnectionPort;
    }

    private int getHttpsPort() throws KeeperException, InterruptedException, IOException {
        return httpsConnectors.isEmpty() ? DEFAULT_HTTPS_PORT : httpsConnectors.iterator().next().getPort();
    }


    /**
     * Returns the global resolution policy.
     */
    private String getGlobalResolutionPolicy(RuntimeProperties sysprops, CuratorFramework zooKeeper) throws Exception {
        String policy = ZkDefs.LOCAL_HOSTNAME;
        List<String> validResolverList = Arrays.asList(ZkDefs.VALID_RESOLVERS);
        if (ZooKeeperUtils.exists(zooKeeper, ZkPath.POLICIES.getPath(ZkDefs.RESOLVER)) != null) {
            policy = ZooKeeperUtils.getStringData(zooKeeper, ZkPath.POLICIES.getPath(ZkDefs.RESOLVER));
        } else if (sysprops.getProperty(ZkDefs.GLOBAL_RESOLVER_PROPERTY) != null && validResolverList.contains(sysprops.getProperty(ZkDefs.GLOBAL_RESOLVER_PROPERTY))) {
            policy = sysprops.getProperty(ZkDefs.GLOBAL_RESOLVER_PROPERTY);
            ZooKeeperUtils.setData(zooKeeper, ZkPath.POLICIES.getPath("resolver"), policy);
        }
        return policy;
    }

    /**
     * Returns the container specific resolution policy.
     */
    private String getContainerResolutionPolicy(CuratorFramework zooKeeper, String container) throws Exception {
        String policy = null;
        List<String> validResolverList = Arrays.asList(ZkDefs.VALID_RESOLVERS);
        if (exists(zooKeeper, ZkPath.CONTAINER_RESOLVER.getPath(container)) != null) {
            policy = getStringData(zooKeeper, ZkPath.CONTAINER_RESOLVER.getPath(container));
        } else if (bootstrapConfiguration.get().getLocalResolver() != null && validResolverList.contains(bootstrapConfiguration.get().getLocalResolver())) {
            policy = bootstrapConfiguration.get().getLocalResolver();
        }
        return policy;
    }

    /**
     * Returns a pointer to the container IP based on the global IP policy.
     *
     * @param curator   The curator client to use to read global policy.
     * @param container The name of the container.
     */
    private static String getContainerPointer(CuratorFramework curator, String container) throws Exception {
        String pointer = "${zk:%s/%s}";
        String resolver = "${zk:%s/resolver}";
        return String.format(pointer, container, String.format(resolver, container));
    }

    void bindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer.bind(mbeanServer);
    }

    void unbindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer.unbind(mbeanServer);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.bind(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.unbind(curator);
    }

    void bindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.bind(service);
    }

    void unbindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.unbind(service);
    }

    void bindPortService(PortService service) {
        this.portService.bind(service);
    }

    void unbindPortService(PortService service) {
        this.portService.unbind(service);
    }

    void bindGeoLocationService(GeoLocationService service) {
        this.geoLocationService.bind(service);
    }

    void unbindGeoLocationService(GeoLocationService service) {
        this.geoLocationService.unbind(service);
    }

    void bindBootstrapConfiguration(BootstrapConfiguration service) {
        this.bootstrapConfiguration.bind(service);
    }

    void unbindBootstrapConfiguration(BootstrapConfiguration service) {
        this.bootstrapConfiguration.unbind(service);
    }
}
