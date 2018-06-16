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
import space.npstr.magma.events.audio.lifecycle.ConnectWebSocketLcEvent;
import space.npstr.magma.events.audio.lifecycle.LifecycleEvent;
import space.npstr.magma.events.audio.lifecycle.Shutdown;
import space.npstr.magma.events.audio.lifecycle.UpdateSendHandler;
import space.npstr.magma.events.audio.lifecycle.VoiceServerUpdate;
import space.npstr.magma.immutables.ImmutableSessionInfo;

import javax.annotation.CheckReturnValue;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Created by napster on 22.04.18.
 * <p>
 * This class manages the lifecycles of various AudioStack objects.
 * <p>
 * The {@link AudioStack} consists of:
 * - A websocket connection ( -> {@link AudioWebSocket}
 * - A voice packet emitter ( -> {@link AudioConnection}
 * - A send handler         ( -> {@link net.dv8tion.jda.core.audio.AudioSendHandler}, provided by user code)
 * - A send system          ( -> {@link net.dv8tion.jda.core.audio.factory.IAudioSendSystem} , provided by user code)
 * <p>
 * <p>
 * Lifecycle Events:
 * <p>
 * - Constructive Events:
 * -- VoiceServerUpdate telling us to connect to a voice server
 * -- Reconnects following certain close events
 * <p>
 * - Destructive Events:
 * -- Websocket close events
 * -- Shutdown
 * <p>
 * Neutral Events:
 * -- Setting and removing a send handler
 */
public class AudioStackLifecyclePipeline {

    private static final Logger log = LoggerFactory.getLogger(AudioStackLifecyclePipeline.class);

    // userId <-> guildId <-> audio stack
    // concurrency is handled by modifying this through a single thread eventloop only
    private final Map<String, Map<String, AudioStack>> audioStacks = new HashMap<>();

    private final Function<Member, AudioSendSystemFactory> sendFactoryProvider;
    private final WebSocketClient webSocketClient;
    private final UdpDiscovery udpDiscovery;

    private final FluxSink<LifecycleEvent> lifecycleEventSink;
    private final Disposable lifecycleSubscription;

    public AudioStackLifecyclePipeline(final Function<Member, AudioSendSystemFactory> sendFactoryProvider,
                                       final WebSocketClient webSocketClient, UdpDiscovery udpDiscovery) {
        this.sendFactoryProvider = sendFactoryProvider;
        this.webSocketClient = webSocketClient;
        this.udpDiscovery = udpDiscovery;

        final UnicastProcessor<LifecycleEvent> processor = UnicastProcessor.create();

        this.lifecycleEventSink = processor.sink();


        this.lifecycleSubscription = processor
                .log(log.getName() + ".Inbound", Level.FINEST) //FINEST = TRACE
                .subscribeOn(Schedulers.single())
                .subscribe(this::onEvent);
    }

    /**
     * Call this to drop lifecycle events into this thing for processing
     */
    public void next(final LifecycleEvent lifecycleEvent) {
        this.lifecycleEventSink.next(lifecycleEvent);
    }


    private void onEvent(final LifecycleEvent event) {

        if (event instanceof VoiceServerUpdate) {
            final VoiceServerUpdate voiceServerUpdate = (VoiceServerUpdate) event;
            this.getAudioStack(event)
                    .next(ConnectWebSocketLcEvent.builder()
                            .sessionInfo(ImmutableSessionInfo.builder()
                                    .voiceServerUpdate(voiceServerUpdate)
                                    .build())
                            .build()
                    );
        } else if (event instanceof UpdateSendHandler) {
            this.getAudioStack(event)
                    .next(event);
        } else if (event instanceof CloseWebSocket) {
            //pass it on
            this.getAudioStack(event)
                    .next(event);
        } else if (event instanceof Shutdown) {
            this.lifecycleSubscription.dispose();

            this.audioStacks.values().stream().flatMap(map -> map.values().stream()).forEach(
                    audioStack -> audioStack.next(event)
            );
        } else {
            log.warn("Unhandled lifecycle event of class {}", event.getClass().getSimpleName());
        }
    }

    @CheckReturnValue
    private AudioStack getAudioStack(final LifecycleEvent lifecycleEvent) {
        return this.audioStacks
                .computeIfAbsent(lifecycleEvent.getUserId(), __ -> new HashMap<>())
                .computeIfAbsent(lifecycleEvent.getGuildId(), __ ->
                        new AudioStack(lifecycleEvent.getGuildId(),
                                this.sendFactoryProvider.apply(lifecycleEvent.getMember()),
                                this.webSocketClient,
                                this.udpDiscovery,
                                this));
    }
}
