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

package space.npstr.magma;

import com.sedmelluq.lava.discord.dispatch.AudioSendSystemFactory;
import com.sedmelluq.lava.discord.dispatch.OpusFrameProvider;
import com.sedmelluq.lava.discord.reactor.udp.UdpDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.scheduler.Schedulers;
import space.npstr.magma.connections.AudioWebSocket;
import space.npstr.magma.events.audio.lifecycle.CloseWebSocket;
import space.npstr.magma.events.audio.lifecycle.ConnectWebSocket;
import space.npstr.magma.events.audio.lifecycle.LifecycleEvent;
import space.npstr.magma.events.audio.lifecycle.Shutdown;
import space.npstr.magma.events.audio.lifecycle.UpdateSendHandler;

import javax.annotation.Nullable;

/**
 * Created by napster on 23.04.18.
 * <p>
 * One of these per user and guild. Glues together the SendHandler and the Connections (websocket + udp).
 *
 * @see AudioStackLifecyclePipeline
 */
public class AudioStack {

    private static final Logger log = LoggerFactory.getLogger(AudioStack.class);

    private final String guildId;
    private final AudioSendSystemFactory sendFactory;
    private final WebSocketClient webSocketClient;
    private final UdpDiscovery udpDiscovery;
    private final AudioStackLifecyclePipeline lifecyclePipeline;

    private final FluxSink<LifecycleEvent> lifecycleSink;
    private final Disposable lifecycleSubscription;

    @Nullable
    private AudioWebSocket webSocket;
    @Nullable
    private OpusFrameProvider sendHandler;


    public AudioStack(final String guildId, final AudioSendSystemFactory sendFactory,
                      final WebSocketClient webSocketClient, final UdpDiscovery udpDiscovery,
                      final AudioStackLifecyclePipeline lifecyclePipeline) {
        this.guildId = guildId;
        this.sendFactory = sendFactory;
        this.webSocketClient = webSocketClient;
        this.udpDiscovery = udpDiscovery;
        this.lifecyclePipeline = lifecyclePipeline;

        final UnicastProcessor<LifecycleEvent> lifecycleProcessor = UnicastProcessor.create();
        this.lifecycleSink = lifecycleProcessor.sink();
        this.lifecycleSubscription = lifecycleProcessor
                .subscribeOn(Schedulers.single())
                .subscribe(this::onNext);
    }


    public void next(final LifecycleEvent event) {
        if (!(event instanceof Shutdown)
                && !event.getGuildId().equals(this.guildId)) {
            throw new IllegalArgumentException(String.format("Passed a lifecycle event for guild %s to the audio stack of guild %s",
                    event.getGuildId(), this.guildId));
        }

        this.lifecycleSink.next(event);
    }


    //distribute events to the handler methods below
    private void onNext(final LifecycleEvent event) {

        if (event instanceof ConnectWebSocket) {
            this.handleConnectWebSocket((ConnectWebSocket) event);
        } else if (event instanceof UpdateSendHandler) {
            this.handleUpdateSendHandler((UpdateSendHandler) event);
        } else if (event instanceof CloseWebSocket) {
            this.handleCloseWebSocket((CloseWebSocket) event);
        } else if (event instanceof Shutdown) {
            this.handleShutdown((Shutdown) event);
        } else {
            log.warn("Audiostack has no handler for lifecycle event of class {}", event.getClass().getSimpleName());
        }

    }


    private void handleConnectWebSocket(final ConnectWebSocket connectWebSocket) {
        if (this.webSocket != null) {
            this.webSocket.close();
        }

        this.webSocket = new AudioWebSocket(this.sendFactory, connectWebSocket.getSessionInfo(),
                this.webSocketClient, this.udpDiscovery, this.lifecyclePipeline, () -> sendHandler);
    }

    private void handleUpdateSendHandler(final UpdateSendHandler updateSendHandler) {
        this.sendHandler = updateSendHandler.getAudioSendHandler().orElse(null);

        if (this.webSocket != null) {
            this.webSocket.onFrameProviderUpdated();
        }
    }

    private void handleCloseWebSocket(final CloseWebSocket closeWebSocket) {
        if (this.webSocket != null) {
            this.webSocket.close();
            this.webSocket = null;
        }
    }

    private void handleShutdown(final Shutdown shutdown) {
        this.lifecycleSubscription.dispose();
        if (this.webSocket != null) {
            this.webSocket.close();
            this.webSocket = null;
        }
        this.sendHandler = null;
    }
}
