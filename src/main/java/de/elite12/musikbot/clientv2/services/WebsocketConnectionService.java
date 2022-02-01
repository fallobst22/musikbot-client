package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.CommandEvent;
import de.elite12.musikbot.clientv2.events.RequestSongEvent;
import de.elite12.musikbot.clientv2.util.CommandConsumer;
import de.elite12.musikbot.shared.clientDTO.ClientDTO;
import de.elite12.musikbot.shared.clientDTO.SimpleCommand;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.lang.reflect.Type;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;

@Service
public class WebsocketConnectionService implements StompFrameHandler, StompSessionHandler, ApplicationListener<RequestSongEvent> {

    private final Logger logger = LoggerFactory.getLogger(WebsocketConnectionService.class);

    @Autowired
    private Clientv2ServiceProperties clientv2ServiceProperties;
    private final BlockingQueue<ClientDTO> commandQueue;
    @Autowired
    private OAuth2AuthorizedClientManager oAuth2AuthorizedClientManager;
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private final WebSocketStompClient webSocketStompClient;
    @Autowired
    private AsyncTaskExecutor taskExecutor;
    private Future<?> consumerThread = null;

    public WebsocketConnectionService(TaskScheduler messageBrokerTaskScheduler) {
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        this.webSocketStompClient = new WebSocketStompClient(webSocketClient);
        this.webSocketStompClient.setMessageConverter(new MappingJackson2MessageConverter());
        this.webSocketStompClient.setTaskScheduler(messageBrokerTaskScheduler);
        this.webSocketStompClient.setDefaultHeartbeat(new long[]{0, 25000});
        this.commandQueue = new ArrayBlockingQueue<>(20);
    }

    @PostConstruct
    public void postConstruct() {
        this.connect();
    }

    @PreDestroy
    public void preDestroy() {
        this.webSocketStompClient.stop();
    }

    private void connect() {
        WebSocketHttpHeaders webSocketHttpHeaders = new WebSocketHttpHeaders();
        webSocketHttpHeaders.setBearerAuth(oAuth2AuthorizedClientManager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("musikbot").principal("musikbot").build()).getAccessToken().getTokenValue());
        this.webSocketStompClient.connect(clientv2ServiceProperties.getServerurl(), webSocketHttpHeaders, this);
    }

    @Override
    public @NotNull Type getPayloadType(StompHeaders headers) {
        try {
            String type = headers.getFirst("type");
            if (type == null) throw new ClassNotFoundException("Type Header missing");
            return Class.forName("de.elite12.musikbot.shared.clientDTO." + type);
        } catch (ClassNotFoundException e) {
            logger.error("Error parsing Message Type", e);
            return Object.class;
        }
    }

    @Override
    public void handleFrame(@NotNull StompHeaders headers, Object payload) {
        this.applicationEventPublisher.publishEvent(new CommandEvent(this, (ClientDTO) payload));
    }

    @Override
    public void afterConnected(StompSession session, @NotNull StompHeaders connectedHeaders) {
        logger.info("Connected to Server");
        session.subscribe("/topic/client", this);
        this.consumerThread = this.taskExecutor.submit(new CommandConsumer(this.commandQueue, session));
    }

    @Override
    public void handleException(@NotNull StompSession session, StompCommand command, @NotNull StompHeaders headers, byte @NotNull [] payload, @NotNull Throwable exception) {
        logger.error("Exception", exception);
    }

    @Override
    public void handleTransportError(@NotNull StompSession session, @NotNull Throwable exception) {
        if (exception instanceof ConnectionLostException) {
            logger.error("Lost websocket connection", exception);
        } else {
            logger.error("Error Connecting to Server", exception);
        }

        if (this.consumerThread != null && !this.consumerThread.isDone()) this.consumerThread.cancel(true);

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                connect();
            }
        }, 5000);
    }

    public void sendCommand(ClientDTO command) {
        this.commandQueue.add(command);
    }

    @Override
    public void onApplicationEvent(@NotNull RequestSongEvent event) {
        this.sendCommand(new SimpleCommand(SimpleCommand.CommandType.REQUEST_SONG));
    }
}
