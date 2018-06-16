/*
 * Copyright 2018 Dennis Neufeld
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package space.npstr.magma.connections;

import com.sedmelluq.lava.discord.dispatch.AudioSendSystemFactory;
import com.sedmelluq.lava.discord.dispatch.OpusFrameProvider;
import com.sedmelluq.lava.discord.dispatch.packet.AudioPacketBuilder;
import com.sedmelluq.lava.discord.dispatch.packet.AudioPacketProviderHolder;
import com.sedmelluq.lava.discord.reactor.udp.UdpDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.scheduler.Schedulers;
import space.npstr.magma.AudioStackLifecyclePipeline;
import space.npstr.magma.EncryptionMode;
import space.npstr.magma.events.audio.lifecycle.CloseWebSocketLcEvent;
import space.npstr.magma.events.audio.ws.CloseCode;
import space.npstr.magma.events.audio.ws.Speaking;
import space.npstr.magma.events.audio.ws.SpeakingWsEvent;
import space.npstr.magma.events.audio.ws.in.ClientDisconnect;
import space.npstr.magma.events.audio.ws.in.HeartbeatAck;
import space.npstr.magma.events.audio.ws.in.Hello;
import space.npstr.magma.events.audio.ws.in.Ignored;
import space.npstr.magma.events.audio.ws.in.InboundWsEvent;
import space.npstr.magma.events.audio.ws.in.Ready;
import space.npstr.magma.events.audio.ws.in.Resumed;
import space.npstr.magma.events.audio.ws.in.SessionDescription;
import space.npstr.magma.events.audio.ws.in.UnknownWsEvent;
import space.npstr.magma.events.audio.ws.in.WebSocketClosed;
import space.npstr.magma.events.audio.ws.out.HeartbeatWsEvent;
import space.npstr.magma.events.audio.ws.out.IdentifyWsEvent;
import space.npstr.magma.events.audio.ws.out.OutboundWsEvent;
import space.npstr.magma.events.audio.ws.out.ResumeWsEvent;
import space.npstr.magma.events.audio.ws.out.SelectProtocolWsEvent;
import space.npstr.magma.immutables.SessionInfo;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static com.sedmelluq.lava.discord.dispatch.packet.AudioPacketBuilder.NonceStrategy.INCREMENTING_INT;
import static com.sedmelluq.lava.discord.dispatch.packet.AudioPacketBuilder.NonceStrategy.PACKET_HEADER;
import static com.sedmelluq.lava.discord.dispatch.packet.AudioPacketBuilder.NonceStrategy.RANDOM_SEQUENCE;

/**
 * Created by napster on 19.04.18.
 *
 * Handle the lifecycle of the Discord voice websocket connection.
 */
public class AudioWebSocket extends BaseSubscriber<InboundWsEvent> {

    private static final Logger log = LoggerFactory.getLogger(AudioWebSocket.class);

    private final SessionInfo session;
    private final URI wssEndpoint;
    private final UdpDiscovery udpDiscovery;
    private final AudioStackLifecyclePipeline lifecyclePipeline;
    private final WebSocketClient webSocketClient;

    //drop events into this sink to have them sent to discord
    private final FluxSink<OutboundWsEvent> audioWebSocketSink;
    //reusable, if prepareConnect() is called before reconnecting
    private final AudioWebSocketSessionHandler webSocketHandler;

    private final AudioPacketProviderHolder packetProviderHolder;

    @Nullable
    private Disposable heartbeatSubscription;
    private Disposable webSocketConnection;


    public AudioWebSocket(final AudioSendSystemFactory sendSystemFactory, final SessionInfo session,
                          final WebSocketClient webSocketClient, final UdpDiscovery udpDiscovery,
                          final AudioStackLifecyclePipeline lifecyclePipeline,
                          final Supplier<OpusFrameProvider> frameProviderSupplier) {

        this.packetProviderHolder = new AudioPacketProviderHolder(this::setSpeaking, frameProviderSupplier, sendSystemFactory);
        this.session = session;
        try {
            this.wssEndpoint = new URI(String.format("wss://%s/?v=4", session.getVoiceServerUpdate().getEndpoint()));
        } catch (final URISyntaxException e) {
            throw new RuntimeException("Endpoint " + session.getVoiceServerUpdate().getEndpoint() + " is not a valid URI", e);
        }
        this.udpDiscovery = udpDiscovery;
        this.lifecyclePipeline = lifecyclePipeline;
        this.webSocketClient = webSocketClient;


        final UnicastProcessor<OutboundWsEvent> webSocketProcessor = UnicastProcessor.create();
        this.audioWebSocketSink = webSocketProcessor.sink();

        this.webSocketHandler = new AudioWebSocketSessionHandler(webSocketProcessor, this);
        this.webSocketConnection = this.connect(this.webSocketClient, this.wssEndpoint, this.webSocketHandler);
    }

    public void onFrameProviderUpdated() {
        packetProviderHolder.onFrameProviderChanged();
    }

    private void setSpeaking(final boolean isSpeaking) {
        final int speakingMask = isSpeaking ? 1 : 0;
        this.send(SpeakingWsEvent.builder()
                .speakingMask(speakingMask)
                .build());
    }

    public void close() {
        this.closeEverything();
    }

    // ################################################################################
    // #                        Inbound event handlers
    // ################################################################################

    /**
     * Process the incoming events from the websocket.
     */
    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    protected void hookOnNext(final InboundWsEvent inboundEvent) {
        if (inboundEvent instanceof Hello) {
            this.handleHello((Hello) inboundEvent);
        } else if (inboundEvent instanceof Ready) {
            this.handleReady((Ready) inboundEvent);
        } else if (inboundEvent instanceof SessionDescription) {
            this.handleSessionDescription((SessionDescription) inboundEvent);
        } else if (inboundEvent instanceof HeartbeatAck) {
            // noop
        } else if (inboundEvent instanceof WebSocketClosed) {
            this.handleWebSocketClosed((WebSocketClosed) inboundEvent);
        } else if (inboundEvent instanceof ClientDisconnect) {
            // noop
        } else if (inboundEvent instanceof Speaking) {
            // noop
        } else if (inboundEvent instanceof Resumed) {
            // noop
        } else if (inboundEvent instanceof Ignored) {
            log.trace("Ignored OP {}, payload: {}", inboundEvent.getOpCode(), ((Ignored) inboundEvent).getPayload());
        } else if (inboundEvent instanceof UnknownWsEvent) {
            log.warn("Unknown OP {}, payload: {}", inboundEvent.getOpCode(), ((UnknownWsEvent) inboundEvent).getPayload());
        } else {
            log.warn("WebSocket has no handler for event of class {}", inboundEvent.getClass().getSimpleName());
        }
    }

    private void handleHello(final Hello hello) {
        this.heartbeatSubscription = Flux.interval(Duration.ofMillis(hello.getHeartbeatIntervalMillis()))
                .doOnNext(tick -> log.trace("Sending heartbeat {}", tick))
                .subscribeOn(Schedulers.single())
                .subscribe(tick -> this.audioWebSocketSink.next(HeartbeatWsEvent.builder()
                        .nonce(tick.intValue())
                        .build())
                );

        this.audioWebSocketSink.next(IdentifyWsEvent.builder()
                .userId(this.session.getUserId())
                .guildId(this.session.getVoiceServerUpdate().getGuildId())
                .sessionId(this.session.getVoiceServerUpdate().getSessionId())
                .token(this.session.getVoiceServerUpdate().getToken())
                .build()
        );
    }

    private void handleReady(final Ready ready) {
        final InetSocketAddress udpTargetAddress = new InetSocketAddress(ready.getIp(), ready.getPort());
        final List<EncryptionMode> receivedModes = ready.getEncryptionModes();
        final Optional<EncryptionMode> preferredModeOpt = EncryptionMode.getPreferredMode(receivedModes);
        if (!preferredModeOpt.isPresent()) {
            final String modes = receivedModes.isEmpty()
                    ? "empty list"
                    : String.join(", ", receivedModes.stream().map(Enum::name).collect(Collectors.toList()));
            throw new RuntimeException("Failed to select encryption modes from " + modes); //todo how are exceptions handled?
        }
        final EncryptionMode preferredMode = preferredModeOpt.get();
        log.debug("Selecting encryption mode {}", preferredMode);

        packetProviderHolder.onAddressAndSsrcChanged(udpTargetAddress, ready.getSsrc());
        udpDiscovery.handleUdpDiscovery(udpTargetAddress, ready.getSsrc())
                .timeout(Duration.ofSeconds(2))
                .subscribeOn(Schedulers.single())
                .doOnError(t -> log.error("Exception during UDP discovery.", t))
                .subscribe(externalAddress -> this.audioWebSocketSink.next(
                        SelectProtocolWsEvent.builder()
                                .protocol("udp")
                                .host(externalAddress.getHostString())
                                .port(externalAddress.getPort())
                                .encryptionMode(preferredMode)
                                .build()));
    }

    private void handleSessionDescription(final SessionDescription sessionDescription) {
        packetProviderHolder.onKeyAndStrategyChanged(sessionDescription.getSecretKey(),
            getNonceStrategy(sessionDescription.getEncryptionMode()));
    }

    private static AudioPacketBuilder.NonceStrategy getNonceStrategy(EncryptionMode encryptionMode) {
        switch (encryptionMode) {
            case XSALSA20_POLY1305_LITE:
                return INCREMENTING_INT;
            case XSALSA20_POLY1305_SUFFIX:
                return RANDOM_SEQUENCE;
            case XSALSA20_POLY1305:
                return PACKET_HEADER;
        }

        throw new IllegalArgumentException();
    }

    private void handleWebSocketClosed(final WebSocketClosed webSocketClosed) {
        final int code = webSocketClosed.getCode();
        log.info("Websocket closed with code {} and reason {}", code, webSocketClosed.getReason());

        final boolean resume = (code == CloseCode.DISCONNECTED // according to discord docs
                || code == CloseCode.VOICE_SERVER_CRASHED);    // according to discord docs

        if (resume) {
            log.info("Resuming");
            this.webSocketConnection.dispose();
            this.webSocketHandler.prepareConnect();
            this.webSocketConnection = this.connect(this.webSocketClient, this.wssEndpoint, this.webSocketHandler);
            this.audioWebSocketSink.next(ResumeWsEvent.builder()
                    .guildId(this.session.getVoiceServerUpdate().getGuildId())
                    .sessionId(this.session.getVoiceServerUpdate().getSessionId())
                    .token(this.session.getVoiceServerUpdate().getToken())
                    .build());
        } else {
            log.info("Closing");
            this.lifecyclePipeline.next(CloseWebSocketLcEvent.builder()
                    .member(this.session.getVoiceServerUpdate().getMember())
                    .build());
        }
    }

    // ################################################################################
    // #                                Internals
    // ################################################################################

    private Disposable connect(final WebSocketClient client, final URI endpoint, final WebSocketHandler handler) {
        return client.execute(endpoint, handler)
                .log(log.getName() + ".WebSocketConnection", Level.FINEST) //FINEST = TRACE
                .doOnError(t -> {
                    log.error("Exception in websocket connection, closing", t);
                    this.closeEverything();
                })
                .subscribeOn(Schedulers.single())
                .subscribe();
    }

    private void send(final OutboundWsEvent outboundWsEvent) {
        this.audioWebSocketSink.next(outboundWsEvent);
    }

    private void closeEverything() {
        log.trace("Closing everything");
        this.webSocketHandler.close();
        this.webSocketConnection.dispose();
        if (this.heartbeatSubscription != null) {
            this.heartbeatSubscription.dispose();
        }
        packetProviderHolder.shutdown();
    }
}
