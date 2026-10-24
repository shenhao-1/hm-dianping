package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.STREAM_ORDERS_CONSUMER;
import static com.hmdp.utils.RedisConstants.STREAM_ORDERS_GROUP;
import static com.hmdp.utils.RedisConstants.STREAM_ORDERS_KEY;

/**
 * Voucher order service implementation.
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoucherOrderServiceImpl.class);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * One worker consumes Redis Stream messages and writes accepted orders to MySQL.
     */
    private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "seckill-stream-worker");
        thread.setDaemon(true);
        return thread;
    });

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    private void init() {
        createStreamConsumerGroup();
        seckillOrderExecutor.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void shutdown() {
        seckillOrderExecutor.shutdownNow();
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("not login");
        }

        // Keep the time-window validation. Lua handles stock and duplicate orders.
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("voucher not found");
        }

        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime().isAfter(now)) {
            return Result.fail("seckill has not started");
        }
        if (voucher.getEndTime().isBefore(now)) {
            return Result.fail("seckill has ended");
        }

        Long userId = user.getId();
        String stockKey = SECKILL_STOCK_KEY + voucherId;

        // Existing vouchers are initialized lazily; newly created vouchers are initialized on creation.
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey, voucher.getStock().toString());

        // The order id is generated first and is written to the Stream by the same Lua transaction.
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, SECKILL_ORDER_KEY + voucherId, STREAM_ORDERS_KEY),
                userId.toString(),
                voucherId.toString(),
                Long.toString(orderId)
        );

        int resultCode = result == null ? -1 : result.intValue();
        if (resultCode == 1) {
            return Result.fail("stock is not enough");
        }
        if (resultCode == 2) {
            return Result.fail("do not repeat order");
        }
        if (resultCode != 0) {
            return Result.fail("seckill service is busy");
        }

        return Result.ok(orderId);
    }

    /**
     * Creates an order in one database transaction. Only the Stream worker calls this method.
     */
    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();

        // Retain the database check as the final one-user-one-order safeguard.
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            LOGGER.warn("Duplicate seckill order ignored, userId={}, voucherId={}", userId, voucherId);
            return;
        }

        // This stock > 0 condition is the database optimistic lock against overselling.
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            LOGGER.warn("Database stock deduction failed, voucherId={}", voucherId);
            return;
        }

        if (!save(voucherOrder)) {
            throw new IllegalStateException("Failed to save voucher order " + voucherOrder.getId());
        }
    }

    /**
     * Returns false when the distributed lock is temporarily unavailable.
     * The Stream message is then left unacknowledged for a later retry.
     */
    private boolean handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId + ":" + voucherId);
        boolean isLock;
        try {
            // Without a fixed lease time, Redisson's watchdog renews the lock while work is running.
            isLock = lock.tryLock(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!isLock) {
            LOGGER.warn("Failed to acquire order lock, userId={}, voucherId={}", userId, voucherId);
            return false;
        }

        try {
            transactionTemplate.execute(status -> {
                createVoucherOrder(voucherOrder);
                return null;
            });
            return true;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Creates the consumer group once. MKSTREAM also creates the Stream when it does not exist.
     */
    private void createStreamConsumerGroup() {
        try {
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    raw("CREATE"),
                    raw(STREAM_ORDERS_KEY),
                    raw(STREAM_ORDERS_GROUP),
                    raw("0"),
                    raw("MKSTREAM")
            ));
        } catch (DataAccessException e) {
            if (!containsMessage(e, "BUSYGROUP")) {
                throw e;
            }
        }
    }

    private byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private boolean containsMessage(Throwable throwable, String expected) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private VoucherOrder readVoucherOrder(Map<Object, Object> values) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(Long.valueOf(values.get("id").toString()));
        voucherOrder.setUserId(Long.valueOf(values.get("userId").toString()));
        voucherOrder.setVoucherId(Long.valueOf(values.get("voucherId").toString()));
        voucherOrder.setPayType(1);
        voucherOrder.setStatus(1);
        return voucherOrder;
    }

    private void processAndAcknowledge(MapRecord<String, Object, Object> record) {
        VoucherOrder voucherOrder = readVoucherOrder(record.getValue());
        if (!handleVoucherOrder(voucherOrder)) {
            throw new IllegalStateException("Order lock is busy: " + voucherOrder.getId());
        }
        stringRedisTemplate.opsForStream().acknowledge(
                STREAM_ORDERS_KEY,
                STREAM_ORDERS_GROUP,
                record.getId()
        );
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            // Recover messages that were delivered before a previous process stopped.
            handlePendingList();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_ORDERS_GROUP, STREAM_ORDERS_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    processAndAcknowledge(records.get(0));
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    LOGGER.error("Failed to consume a new seckill order message", e);
                    handlePendingList();
                }
            }
        }

        /**
         * Reads this consumer's Pending List from offset 0 and retries until it is empty.
         */
        private void handlePendingList() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_ORDERS_GROUP, STREAM_ORDERS_CONSUMER),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.from("0"))
                    );
                    if (records == null || records.isEmpty()) {
                        return;
                    }
                    processAndAcknowledge(records.get(0));
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    LOGGER.error("Failed to retry a pending seckill order message", e);
                    try {
                        TimeUnit.MILLISECONDS.sleep(100);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }
}