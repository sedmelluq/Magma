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

import com.sedmelluq.lava.discord.dispatch.OpusFrameProvider;
import com.sedmelluq.lava.discord.dispatch.AudioSendSystemFactory;
import org.xnio.OptionMap;
import org.xnio.XnioWorker;

import java.util.function.Function;

/**
 * Created by napster on 24.04.18.
 * <p>
 * Public API. These methods may be called by users of Magma.
 */
public interface MagmaApi {

    /**
     * Please see full factory documentation below. Missing parameters on this factory method are optional.
     */
    static MagmaApi of(final Function<Member, AudioSendSystemFactory> sendFactoryProvider) {
        return of(sendFactoryProvider, OptionMap.builder().getMap());
    }

    /**
     * Create a new Magma instance. More than one of these is not necessary, even if you are managing several shards and
     * several bot accounts. A single instance of this scales automatically according to your needs and hardware.
     *
     * @param sendFactoryProvider
     *         a provider of {@link AudioSendSystemFactory}s. It will have members applied to it.
     * @param xnioOptions
     *         options to build the {@link XnioWorker} that will be used for the websocket connections
     */
    static MagmaApi of(final Function<Member, AudioSendSystemFactory> sendFactoryProvider,
                       final OptionMap xnioOptions) {
        return new Magma(sendFactoryProvider, xnioOptions);
    }

    /**
     * Release all resources held.
     */
    void shutdown();

    /**
     * Also see: https://discordapp.com/developers/docs/topics/voice-connections#retrieving-voice-server-information-example-voice-server-update-payload
     *
     * @param member
     *         Id of the bot account that this update belongs to composed with the id of the guild whose voice server
     *         shall be updated. The user id is something your use code should keep track of, the guild id can be
     *         extracted from the op 0 VOICE_SERVER_UPDATE event that should be triggering a call to this method in the
     *         first place.
     * @param serverUpdate
     *         A composite of session id, endpoint and token. Most of that information can be extracted from the op 0
     *         VOICE_SERVER_UPDATE event that should be triggering a call to this method in the first place.
     *
     * @see Member
     * @see ServerUpdate
     */
    void provideVoiceServerUpdate(final Member member, final ServerUpdate serverUpdate);

    /**
     * Set the {@link OpusFrameProvider} for a bot member.
     *
     * @param member
     *         user id + guild id of the bot member for which the send handler shall be set
     * @param sendHandler
     *         The send handler to be set. You need to implement this yourself. This is a JDA interface so if you have
     *         written voice code with JDA before you reuse your existing code.
     *
     * @see Member
     */
    void setSendHandler(final Member member, final OpusFrameProvider sendHandler);

    /**
     * Remove the {@link OpusFrameProvider} for a bot member.
     *
     * @param member
     *         user id + guild id of the bot member for which the send handler shall be removed
     *
     * @see Member
     */
    void removeSendHandler(final Member member);

    /**
     * Close the audio connection for a bot member.
     *
     * @param member
     *         user id + guild id of the bot member for which the audio connection shall be closed
     *
     * @see Member
     */
    void closeConnection(final Member member);

}
