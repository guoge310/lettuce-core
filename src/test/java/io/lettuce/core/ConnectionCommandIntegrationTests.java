/*
 * Copyright 2011-2019 the original author or authors.
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
package io.lettuce.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.test.*;

/**
 * @author Will Glozer
 * @author Mark Paluch
 */
@ExtendWith(LettuceExtension.class)
class ConnectionCommandIntegrationTests extends TestSupport {

    private final RedisClient client;
    private final RedisCommands<String, String> redis;

    @Inject
    ConnectionCommandIntegrationTests(RedisClient client, StatefulRedisConnection<String, String> connection) {
        this.client = client;
        this.redis = connection.sync();
    }

    @BeforeEach
    void setUp() {
        redis.flushall();
    }

    @Test
    void auth() {

        WithPassword.run(client, () -> {
            client.setOptions(
                    ClientOptions.builder().pingBeforeActivateConnection(false).protocolVersion(ProtocolVersion.RESP2).build());
            RedisCommands<String, String> connection = client.connect().sync();
            try {
                connection.ping();
                fail("Server doesn't require authentication");
            } catch (RedisException e) {
                assertThat(e.getMessage()).isEqualTo("NOAUTH Authentication required.");
                assertThat(connection.auth(passwd)).isEqualTo("OK");
                assertThat(connection.set(key, value)).isEqualTo("OK");
            }

            RedisURI redisURI = RedisURI.Builder.redis(host, port).withDatabase(2).withPassword(passwd).build();
            RedisCommands<String, String> authConnection = client.connect(redisURI).sync();
            authConnection.ping();
            authConnection.getStatefulConnection().close();
        });
    }

    @Test
    void echo() {
        assertThat(redis.echo("hello")).isEqualTo("hello");
    }

    @Test
    void ping() {
        assertThat(redis.ping()).isEqualTo("PONG");
    }

    @Test
    void select() {
        redis.set(key, value);
        assertThat(redis.select(1)).isEqualTo("OK");
        assertThat(redis.get(key)).isNull();
    }

    @Test
    void authNull() {
        assertThatThrownBy(() -> redis.auth(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authEmpty() {
        assertThatThrownBy(() -> redis.auth("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authReconnect() {
        WithPassword.run(client, () -> {

            client.setOptions(
                    ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).pingBeforeActivateConnection(false).build());
            RedisCommands<String, String> connection = client.connect().sync();
            assertThat(connection.auth(passwd)).isEqualTo("OK");
            assertThat(connection.set(key, value)).isEqualTo("OK");
            connection.quit();

            Delay.delay(Duration.ofMillis(100));
            assertThat(connection.get(key)).isEqualTo(value);

            connection.getStatefulConnection().close();
        });
    }

    @Test
    void selectReconnect() {
        redis.select(1);
        redis.set(key, value);
        redis.quit();

        Wait.untilTrue(redis::isOpen).waitOrTimeout();
        assertThat(redis.get(key)).isEqualTo(value);
    }

    @Test
    void getSetReconnect() {
        redis.set(key, value);
        redis.quit();
        Wait.untilTrue(redis::isOpen).waitOrTimeout();
        assertThat(redis.get(key)).isEqualTo(value);
    }

    @Test
    void authInvalidPassword() {
        RedisAsyncCommands<String, String> async = client.connect().async();
        try {
            TestFutures.awaitOrTimeout(async.auth("invalid"));
            fail("Authenticated with invalid password");
        } catch (RedisException e) {
            assertThat(e.getMessage()).startsWith("ERR").contains("AUTH");
            StatefulRedisConnectionImpl<String, String> statefulRedisCommands = (StatefulRedisConnectionImpl) async
                    .getStatefulConnection();
            assertThat(statefulRedisCommands.getConnectionState()).extracting("password").isNull();
        } finally {
            async.getStatefulConnection().close();
        }
    }

    @Test
    void selectInvalid() {
        RedisAsyncCommands<String, String> async = client.connect().async();
        try {
            TestFutures.awaitOrTimeout(async.select(1024));
            fail("Selected invalid db index");
        } catch (RedisException e) {
            assertThat(e.getMessage()).startsWith("ERR");
            StatefulRedisConnectionImpl<String, String> statefulRedisCommands = (StatefulRedisConnectionImpl) async
                    .getStatefulConnection();
            assertThat(statefulRedisCommands.getConnectionState()).extracting("db").isEqualTo(0);
        } finally {
            async.getStatefulConnection().close();
        }
    }

    @Test
    void testDoubleToString() {

        assertThat(LettuceStrings.string(1.1)).isEqualTo("1.1");
        assertThat(LettuceStrings.string(Double.POSITIVE_INFINITY)).isEqualTo("+inf");
        assertThat(LettuceStrings.string(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
    }
}
