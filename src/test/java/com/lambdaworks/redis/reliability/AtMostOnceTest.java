package com.lambdaworks.redis.reliability;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.lambdaworks.redis.*;
import com.lambdaworks.redis.api.sync.RedisCommands;
import com.lambdaworks.redis.codec.Utf8StringCodec;
import com.lambdaworks.redis.output.CommandOutput;
import com.lambdaworks.redis.output.IntegerOutput;
import com.lambdaworks.redis.output.StatusOutput;
import com.lambdaworks.redis.protocol.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 * @since 03.07.15 13:40
 */
public class AtMostOnceTest extends AbstractRedisClientTest {

    protected final Utf8StringCodec CODEC = new Utf8StringCodec();
    protected String key = "key";

    @Before
    public void before() throws Exception {
        client.setOptions(new ClientOptions.Builder().autoReconnect(false).build());

        // needs to be increased on slow systems...perhaps...
        client.setDefaultTimeout(3, TimeUnit.SECONDS);

        RedisCommands<String, String> connection = client.connect().sync();
        connection.flushall();
        connection.flushdb();
        connection.close();
    }

    @Test
    public void connectionIsConnectedAfterConnect() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();

        assertThat(getConnectionState(getRedisChannelHandler(connection)));

        connection.close();
    }

    @Test
    public void noReconnectHandler() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();

        assertThat(getHandler(RedisChannelWriter.class, getRedisChannelHandler(connection))).isNotNull();
        assertThat(getHandler(ConnectionWatchdog.class, getRedisChannelHandler(connection))).isNull();

        connection.close();
    }

    @Test
    public void basicOperations() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();

        connection.set(key, "1");
        assertThat(connection.get("key")).isEqualTo("1");

        connection.close();
    }

    @Test
    public void noBufferedCommandsAfterExecute() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();

        connection.set(key, "1");

        assertThat(getQueue(getRedisChannelHandler(connection))).isEmpty();
        assertThat(getCommandBuffer(getRedisChannelHandler(connection))).isEmpty();

        connection.close();
    }

    @Test
    public void commandIsExecutedOnce() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();

        connection.set(key, "1");
        connection.incr(key);
        assertThat(connection.get(key)).isEqualTo("2");

        connection.incr(key);
        assertThat(connection.get(key)).isEqualTo("3");

        connection.incr(key);
        assertThat(connection.get(key)).isEqualTo("4");

        connection.close();
    }

    @Test
    public void commandNotExecutedFailsOnEncode() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();
        RedisChannelWriter<String, String> channelWriter = getRedisChannelHandler(connection).getChannelWriter();

        connection.set(key, "1");
        AsyncCommand<String, String, String> working = new AsyncCommand<>(new Command<String, String, String>(CommandType.INCR,
                new IntegerOutput(CODEC), new CommandArgs<String, String>(CODEC).addKey(key)));
        channelWriter.write(working);
        assertThat(working.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(connection.get(key)).isEqualTo("2");

        AsyncCommand<String, String, Object> command = new AsyncCommand<String, String, Object>(
                new Command<String, String, Object>(CommandType.INCR, new IntegerOutput(CODEC),
                        new CommandArgs<String, String>(CODEC).addKey(key))) {

            @Override
            public CommandOutput<String, String, Object> getOutput() {
                if (true) {
                    throw new IllegalStateException("I want to break free");
                }
                return super.getOutput();
            }
        };

        channelWriter.write(command);

        assertThat(command.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isTrue();
        assertThat(getException(command)).isInstanceOf(IllegalStateException.class);

        assertThat(connection.get(key)).isEqualTo("2");

        assertThat(getQueue(getRedisChannelHandler(connection))).isEmpty();
        assertThat(getCommandBuffer(getRedisChannelHandler(connection))).isEmpty();

        connection.close();
    }

    @Test
    public void commandNotExecutedChannelClosesWhileFlush() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();
        RedisCommands<String, String> verificationConnection = client.connect().sync();
        RedisChannelWriter<String, String> channelWriter = getRedisChannelHandler(connection).getChannelWriter();

        connection.set(key, "1");
        assertThat(verificationConnection.get(key)).isEqualTo("1");

        final CountDownLatch block = new CountDownLatch(1);

        AsyncCommand<String, String, Object> command = new AsyncCommand<String, String, Object>(new Command<>(CommandType.INCR,
                new IntegerOutput(CODEC), new CommandArgs<>(CODEC).addKey(key))) {

            @Override
            public void encode(ByteBuf buf) {
                try {
                    block.await();
                } catch (InterruptedException e) {
                }
                super.encode(buf);
            }
        };

        channelWriter.write(command);

        Channel channel = getChannel(getRedisChannelHandler(connection));
        channel.unsafe().disconnect(channel.newPromise());

        assertThat(channel.isOpen()).isFalse();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isFalse();
        block.countDown();
        assertThat(command.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isTrue();

        assertThat(verificationConnection.get(key)).isEqualTo("1");

        assertThat(getQueue(getRedisChannelHandler(connection))).isEmpty();
        assertThat(getCommandBuffer(getRedisChannelHandler(connection))).isEmpty();

        connection.close();
    }

    @Test
    public void commandFailsDuringDecode() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();
        RedisChannelWriter<String, String> channelWriter = getRedisChannelHandler(connection).getChannelWriter();
        RedisCommands<String, String> verificationConnection = client.connect().sync();

        connection.set(key, "1");

        AsyncCommand<String, String, String> command = new AsyncCommand<>(new Command<>(CommandType.INCR, new StatusOutput<>(
                CODEC), new CommandArgs<>(CODEC).addKey(key)));

        channelWriter.write(command);

        assertThat(command.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isTrue();
        assertThat(getException(command)).isInstanceOf(IllegalStateException.class);

        assertThat(verificationConnection.get(key)).isEqualTo("2");
        assertThat(connection.get(key)).isEqualTo("2");

        connection.close();
    }

    @Test
    public void noCommandsExecutedAfterConnectionIsDisconnected() throws Exception {

        RedisCommands<String, String> connection = client.connect().sync();
        connection.quit();

        try {
            connection.incr(key);
        } catch (RedisException e) {
            assertThat(e).isInstanceOf(RedisException.class);
        }

        connection.close();

        connection = client.connect().sync();
        connection.quit();

        try {

            while (connection.isOpen()) {
                Thread.sleep(100);
            }
            connection.incr(key);
        } catch (Exception e) {
            assertThat(e).isExactlyInstanceOf(RedisException.class).hasMessageContaining("reconnect is disabled");
        }

        connection.close();
    }

    private Throwable getException(RedisFuture<?> command) {
        try {
            command.get();
        } catch (InterruptedException e) {
            return e;
        } catch (ExecutionException e) {
            return e.getCause();
        }
        return null;
    }

    private <K, V> RedisChannelHandler<K, V> getRedisChannelHandler(RedisConnection<K, V> sync) {

        InvocationHandler invocationHandler = Proxy.getInvocationHandler(sync);
        return (RedisChannelHandler<K, V>) ReflectionTestUtils.getField(invocationHandler, "connection");
    }

    private <T> T getHandler(Class<T> handlerType, RedisChannelHandler<?, ?> channelHandler) {
        Channel channel = getChannel(channelHandler);
        return (T) channel.pipeline().get((Class) handlerType);
    }

    private Channel getChannel(RedisChannelHandler<?, ?> channelHandler) {
        return (Channel) ReflectionTestUtils.getField(channelHandler.getChannelWriter(), "channel");
    }

    private Queue<?> getQueue(RedisChannelHandler<?, ?> channelHandler) {
        return (Queue<?>) ReflectionTestUtils.getField(channelHandler.getChannelWriter(), "queue");
    }

    private Queue<?> getCommandBuffer(RedisChannelHandler<?, ?> channelHandler) {
        return (Queue<?>) ReflectionTestUtils.getField(channelHandler.getChannelWriter(), "commandBuffer");
    }

    private String getConnectionState(RedisChannelHandler<?, ?> channelHandler) {
        return ReflectionTestUtils.getField(channelHandler.getChannelWriter(), "lifecycleState").toString();
    }
}