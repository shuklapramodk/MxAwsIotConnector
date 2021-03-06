package mxawsiot.impl;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.File;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Created by ako on 4/26/2016.
 */
public class MqttConnector {
    private static Map<String, MqttConnection> mqttHandlers;


    public void setLogger(ILogNode logger) {
        this.logger = logger;
    }

    private ILogNode logger;

    public MqttConnector() {
        try {
            logger = Core.getLogger(MqttConnection.class.getName());
        } catch (Exception e) {
        }
        if (mqttHandlers == null) {
            mqttHandlers = new HashMap();
        }
    }

    public void startListening(String brokerHost, Long brokerPort, String clientId) throws Exception {
        logger.info("MqttConnector.start listening");
        String key = brokerHost + ":" + brokerPort;
        MqttConnection handler = mqttHandlers.containsKey(key) ? mqttHandlers.get(key) : null ;
        if (handler != null)
            handler.startListening();
        else {
            logger.info("no active subscription with this broker, no need to start listening");
        }
    }

    public void subscribe(String brokerHost, Long brokerPort, String topicName, String onMessageMicroflow, String CA, String ClientCertificate, String ClientKey, String CertificatePassword) throws Exception {
        logger.info("MqttConnector.subscribe");
        subscribe(brokerHost, brokerPort, topicName, onMessageMicroflow, CA, ClientCertificate, ClientKey, CertificatePassword, UUID.randomUUID().toString(), true);
    }

    public void subscribe(String brokerHost, Long brokerPort, String topicName, String onMessageMicroflow, String CA, String ClientCertificate, String ClientKey, String CertificatePassword, String clientId, Boolean startWithCleanSession) throws Exception {
        logger.info("MqttConnector.subscribe");
        MqttConnection connection = getMqttConnection(brokerHost, brokerPort, CA, ClientCertificate, ClientKey, CertificatePassword, clientId, startWithCleanSession);
        connection.subscribe(topicName, onMessageMicroflow);
    }

    public void publish(String brokerHost, Long brokerPort, String topicName, String message, String CA, String ClientCertificate, String ClientKey, String CertificatePassword) throws Exception {
        logger.info("MqttConnector.publish");
        MqttConnection connection = getMqttConnection(brokerHost, brokerPort, CA, ClientCertificate, ClientKey, CertificatePassword, null, false);
        connection.publish(topicName, message);
    }

    private MqttConnection getMqttConnection(String brokerHost, Long brokerPort, String CA, String ClientCertificate, String ClientKey, String CertificatePassword, String clientId, Boolean startWithCleanSession) throws Exception {
        String key = brokerHost + ":" + brokerPort;
        MqttConnection handler;
        synchronized (mqttHandlers) {
            logger.info("Number of objects in mqttHandlers map: " + mqttHandlers.size());

            if (!mqttHandlers.containsKey(key)) {
                logger.info("creating new MqttConnection");
                try {
                    handler = new MqttConnection(logger, brokerHost, brokerPort, CA, ClientCertificate, ClientKey, CertificatePassword, clientId, startWithCleanSession);
                    mqttHandlers.put(key, handler);
                } catch (Exception e) {
                    logger.error(e);
                    throw e;
                }

            } else {
                logger.info("Found existing MqttConnection");
                handler = mqttHandlers.get(key);

            }
            logger.info("Number of objects in mqttHandlers map: " + mqttHandlers.size());
        }

        return handler;
    }

    public void unsubscribe(String brokerHost, Long brokerPort, String topicName) throws Exception {
        String key = brokerHost + ":" + brokerPort;
        MqttConnection handler = mqttHandlers.containsKey(key) ? mqttHandlers.get(key) : null ;
        if (handler != null)
            handler.unsubscribe(topicName);
        else {
            logger.info("no active subscription with this broker, unsubscription has no effect");
        }
    }


    private class MqttConnection {
        private ILogNode logger;
        private MqttClient client = null;
        private HashMap<String, MqttSubscription> subscriptions = new HashMap<>();
        private String broker = null;
        private MqttConnectOptions options = null;
        private String clientId = null;
        private MemoryPersistence persistence = null;

        public MqttConnection(ILogNode logger, String brokerHost, Long brokerPort, String CA, String ClientCertificate, String ClientKey, String CertificatePassword, String clientId, Boolean startWithCleanSession) throws Exception {
            logger.info("new MqttConnection");
            this.logger = logger;
            String hostname = InetAddress.getLocalHost().getHostName();
            String xasId = Core.getXASId();
            logger.info("new MqttConnection client id " + clientId + ", clean Session: " + startWithCleanSession);

            boolean useSsl = (ClientCertificate != null && !ClientCertificate.equals(""));
            broker = null;
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(startWithCleanSession);
            connOpts.setConnectionTimeout(60);
            connOpts.setKeepAliveInterval(0);

            if (!useSsl) {
                broker = String.format("tcp://%s:%d", brokerHost, brokerPort);
            } else {
                broker = String.format("ssl://%s:%d", brokerHost, brokerPort);
                try {
                    String resourcesPath = null;
                    try {
                        resourcesPath = Core.getConfiguration().getResourcesPath().getPath();
                        resourcesPath += File.separator;

                    } catch (Exception e) {
                        //testing mode?
                        resourcesPath = "";
                    }
                    logger.info("resourcesPath: " + resourcesPath);
                    connOpts.setSocketFactory(SSLUtil.getSslSocketFactory(
                            resourcesPath + CA,
                            resourcesPath + ClientCertificate,
                            resourcesPath + ClientKey,
                            CertificatePassword
                    ));
                } catch (Exception e) {
                    logger.error(e);
                    throw e;
                }
            }
            this.options = connOpts;
            this.clientId = clientId;
            // memory for keeping messages that cannot be published yet
            this.persistence = new MemoryPersistence();
            //startListening();
        }

        public void startListening() throws Exception {
            logger.info("startListening");
            try {
                this.client = this.client != null? this.client : new MqttClient(this.broker, this.clientId, this.persistence);
                client.setCallback(new MxMqttCallback(logger, client, subscriptions, options));
                if (!client.isConnected()){
                    logger.info("Connecting to broker: " + broker);
                    IMqttToken token = client.connectWithResult(this.options);
                    token.waitForCompletion();
                }
                this.subscriptions.forEach((s, mqttSubscription) -> {
                    try {
                        client.subscribe(s);
                    } catch (Exception e) {
                        logger.error(e);
                    }
                });
                logger.info("Connected");
            } catch (Exception e) {
                logger.error(e);
                throw e;
            }
        }

        public void stopListening() throws Exception {
            logger.info("stopListening");
            try {
                this.client.disconnect();
            } catch (Exception e) {
                logger.error(e);
                throw e;
            }
        }

        public void finalize() {
            logger.info("finalize MqttConnection");
        }

        public boolean isSubscribed(String topic) {
            return subscriptions.containsKey(topic);

        }

        public void subscribe(String topic, String onMessageMicroflow) throws MqttException {
            logger.info("MqttConnection.subscribe");
            try {
                subscriptions.put(topic, new MqttSubscription(topic, onMessageMicroflow));
                if (client != null) {
                    if (!client.isConnected()){
                        logger.info("reconnecting...");
                        IMqttToken token = client.connectWithResult(this.options);
                        token.waitForCompletion();
                    }
                    client.subscribe(topic);
                }
            } catch (Exception e) {
                logger.error(e);
                throw e;
            }

        }

        public void publish(String topic, String message) throws MqttException {
            logger.info(String.format("MqttConnection.publish: %s, %s, %s", topic, message, client.getClientId()));
            try {
                MqttMessage payload = new MqttMessage(message.getBytes());
                //int qos = 1;
                int qos = 0;
                payload.setQos(qos);
                if (client != null) {
                    if (!client.isConnected()) {
                        logger.info("reconnecting...");
                        IMqttToken token = client.connectWithResult(this.options);
                        token.waitForCompletion();
                    }
                    logger.info("Message publishing");
                    client.publish(topic, payload);
                    logger.info("Message published");
                }

            } catch (Exception e) {
                logger.error(String.format("mqtt publish failed: %s", e.getMessage()));
                logger.error(e);
                throw e;
            }
        }

        public void unsubscribe(String topicName) throws MqttException {
            logger.info(String.format("unsubscribe: %s", topicName));
            try {
                subscriptions.remove(topicName);
                if (client != null) {
                    if (!client.isConnected()) {
                        logger.info("reconnecting...");
                        IMqttToken token = client.connectWithResult(this.options);
                        token.waitForCompletion();
                    }
                    client.unsubscribe(topicName);
                }
            } catch (MqttException e) {
                logger.error(e);
                throw e;
            }
        }
    }
}
