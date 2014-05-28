/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.runtime.container.tomcat.registration;

import io.fabric8.api.Container;
import io.fabric8.api.ContainerRegistration;
import io.fabric8.api.FabricException;
import io.fabric8.api.FabricService;
import io.fabric8.api.GeoLocationService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.internal.ContainerImpl;
import io.fabric8.utils.HostUtils;
import io.fabric8.utils.SystemProperties;
import io.fabric8.zookeeper.ZkDefs;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;
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

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();
    @Reference(referenceInterface = GeoLocationService.class)
    private final ValidatingReference<GeoLocationService> geoLocationService = new ValidatingReference<GeoLocationService>();

    private final Set<Connector> httpConnectors = new LinkedHashSet<Connector>();
    private final Set<Connector> httpsConnectors = new LinkedHashSet<Connector>();

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
        String karafName = getKarafName();
        RuntimeProperties sysprops = runtimeProperties.get();
        String version = sysprops.getProperty("fabric.version", ZkDefs.DEFAULT_VERSION);
        String profiles = sysprops.getProperty("fabric.profiles");
        try {
            if (profiles != null) {
                String versionNode = CONFIG_CONTAINER.getPath(karafName);
                String profileNode = CONFIG_VERSIONS_CONTAINER.getPath(version, karafName);
                ZooKeeperUtils.createDefault(curator.get(), versionNode, version);
                ZooKeeperUtils.createDefault(curator.get(), profileNode, profiles);
            }

            checkAlive();

            String domainsNode = CONTAINER_DOMAINS.getPath(karafName);
            if (ZooKeeperUtils.exists(curator.get(), domainsNode)) {
                ZooKeeperUtils.deleteSafe(curator.get(), domainsNode);
            }

            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_BINDADDRESS.getPath(karafName), sysprops.getProperty(ZkDefs.BIND_ADDRESS, "0.0.0.0"));
            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_RESOLVER.getPath(karafName), getContainerResolutionPolicy(sysprops, curator.get(), karafName));
            ZooKeeperUtils.setData(curator.get(), CONTAINER_LOCAL_HOSTNAME.getPath(karafName), HostUtils.getLocalHostName());
            ZooKeeperUtils.setData(curator.get(), CONTAINER_LOCAL_IP.getPath(karafName), HostUtils.getLocalIp());
            ZooKeeperUtils.setData(curator.get(), CONTAINER_IP.getPath(karafName), getContainerPointer(curator.get(), karafName));
            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_GEOLOCATION.getPath(karafName), geoLocationService.get().getGeoLocation());
            //Check if there are addresses specified as system properties and use them if there is not an existing value in the registry.
            //Mostly usable for adding values when creating containers without an existing ensemble.
            for (String resolver : ZkDefs.VALID_RESOLVERS) {
                String address = sysprops.getProperty(resolver);
                String path = CONTAINER_ADDRESS.getPath(karafName, resolver);
                if (address != null && !address.isEmpty() && !ZooKeeperUtils.exists(curator.get(), path)) {
                    ZooKeeperUtils.setData(curator.get(), path, address);
                }
            }
            registerHttp();


            //Set the port range values
            String minimumPort = sysprops.getProperty(ZkDefs.MINIMUM_PORT);
            String maximumPort = sysprops.getProperty(ZkDefs.MAXIMUM_PORT);
            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_PORT_MIN.getPath(karafName), minimumPort);
            ZooKeeperUtils.createDefault(curator.get(), CONTAINER_PORT_MAX.getPath(karafName), maximumPort);
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
        String nodeAlive = CONTAINER_ALIVE.getPath(getKarafName());
        Stat stat = ZooKeeperUtils.getStat(curator.get(), nodeAlive);
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

    private void registerHttp() throws Exception {
        //We are creating a dummy container object, since this might be called before the actual container is ready.
        Container current = getContainer();
        initializeCatalinaConnectors();
        String httpUrl = getHttpUrl(current);
        ZooKeeperUtils.setData(curator.get(), CONTAINER_HTTP.getPath(current.getId()), httpUrl);
    }

    private void initializeCatalinaConnectors() throws MalformedObjectNameException, AttributeNotFoundException, MBeanException, ReflectionException, InstanceNotFoundException {
        Server server = getServer();
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


    private String getHttpUrl(Container container) throws IOException, KeeperException, InterruptedException {
        boolean useHttps = isHttpsEnabled() && !isHttpEnabled();
        String protocol = useHttps ? "https" : "http";
        int httpPort = useHttps ? getHttpsPort() : getHttpPort();
        return getHttpUrl(protocol, container.getId(), httpPort);
    }

    private boolean isHttpEnabled() throws IOException {
        return !httpConnectors.isEmpty();
    }

    private boolean isHttpsEnabled() throws IOException {
        return !httpsConnectors.isEmpty();
    }

    private int getHttpPort() {
        int port = DEFAULT_HTTP_PORT;
        for (Connector connector : httpConnectors) {
            return connector.getPort();
        }
        return port;
    }

    private String getHttpUrl(String protocol, String name, int httpConnectionPort) throws IOException, KeeperException, InterruptedException {
        return protocol + "://${zk:" + name + "/ip}:" + httpConnectionPort;
    }

    private int getHttpsPort() throws KeeperException, InterruptedException, IOException {
        int port = DEFAULT_HTTPS_PORT;
        for (Connector connector : httpsConnectors) {
            return connector.getPort();
        }
        return port;
    }


    /**
     * Returns the global resolution policy.
     */
    private String getGlobalResolutionPolicy(RuntimeProperties sysprops, CuratorFramework zooKeeper) throws Exception {
        String policy = ZkDefs.LOCAL_HOSTNAME;
        List<String> validResolverList = Arrays.asList(ZkDefs.VALID_RESOLVERS);
        String resolverPolicyPath = ZkPath.POLICIES.getPath(ZkDefs.RESOLVER);
        if (ZooKeeperUtils.exists(zooKeeper, resolverPolicyPath)) {
            policy = ZooKeeperUtils.getStringData(zooKeeper, resolverPolicyPath);
        } else {
            String globalResolver = sysprops.getProperty(ZkDefs.GLOBAL_RESOLVER_PROPERTY);
            if (globalResolver != null && validResolverList.contains(globalResolver)) {
                policy = globalResolver;
                ZooKeeperUtils.setData(zooKeeper, resolverPolicyPath, policy);
            }
        }
        return policy;
    }

    /**
     * Returns the container specific resolution policy.
     */
    private String getContainerResolutionPolicy(RuntimeProperties sysprops, CuratorFramework zooKeeper, String container) throws Exception {
        String policy = null;
        List<String> validResolverList = Arrays.asList(ZkDefs.VALID_RESOLVERS);
        String path = CONTAINER_RESOLVER.getPath(container);
        if (ZooKeeperUtils.exists(zooKeeper, path)) {
            policy = ZooKeeperUtils.getStringData(zooKeeper, path);
        } else {
            String localResolver = sysprops.getProperty(ZkDefs.LOCAL_RESOLVER_PROPERTY);
            if (localResolver != null && validResolverList.contains(localResolver)) {
                policy = localResolver;
            }
        }

        if (policy == null) {
            policy = getGlobalResolutionPolicy(sysprops, zooKeeper);
        }

        if (policy != null && !ZooKeeperUtils.exists(zooKeeper, path)) {
            ZooKeeperUtils.setData(zooKeeper, path, policy);
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

    /**
     * Gets the current {@link io.fabric8.api.Container}.
     *
     * @return The current container if registered or a dummy wrapper of the name and ip.
     */
    private Container getContainer() {
        try {
            return fabricService.get().getCurrentContainer();
        } catch (Exception e) {
            final String karafName = getKarafName();
            return new ContainerImpl(null, karafName, null) {
                @Override
                public String getIp() {
                    try {
                        return ZooKeeperUtils.getSubstitutedPath(curator.get(), CONTAINER_IP.getPath(karafName));
                    } catch (Exception e) {
                        throw FabricException.launderThrowable(e);
                    }
                }
            };
        }
    }

    private String getKarafName() {
        return runtimeProperties.get().getProperty(SystemProperties.KARAF_NAME);
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

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindGeoLocationService(GeoLocationService service) {
        this.geoLocationService.bind(service);
    }

    void unbindGeoLocationService(GeoLocationService service) {
        this.geoLocationService.unbind(service);
    }
}
