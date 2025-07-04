package com.wavjaby.jdbc.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;

@Service
public class Snowflake implements IdentifierGenerator {
    private static final Logger logger = LoggerFactory.getLogger(Snowflake.class);

    private final long epoch;

    private static final long workerIdBits = 10L;
    public static final long workerIdMax = ~(-1L << workerIdBits);
    private static final long sequenceBits = 12L;
    public static final long sequenceMax = ~(-1L << sequenceBits);

    private static final long workerIdShift = sequenceBits;
    private static final long timestampLeftShift = sequenceBits + workerIdBits;

    private final long workerId;
    private long lastTimestamp;
    private long sequence = 0;

    public Snowflake() throws SocketException, UnknownHostException {
        this.epoch = 1704067200000L; // 2024-01-01 00:00:00+00:00
        InetAddress localHost = InetAddress.getLocalHost();
        NetworkInterface ni = NetworkInterface.getByInetAddress(localHost);
        byte[] hardwareAddress = ni.getHardwareAddress();
        this.workerId = (Arrays.hashCode(hardwareAddress) & 0x7FFFFFFF) % workerIdMax;
        logger.info("Worker ID: {}", workerId);
        this.lastTimestamp = System.currentTimeMillis();
    }
    
    public Snowflake(long workerId, long epochTimestamp) {
        this.workerId = workerId;
        this.epoch = epochTimestamp;
        this.lastTimestamp = System.currentTimeMillis();
    }

    @Override
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            logger.error("Clock is moving backwards. last timestamp: {}", lastTimestamp);
            System.exit(1);
        }

        if (lastTimestamp == timestamp) {
            // Get next millisecond if sequence is full
            sequence = (sequence + 1) & sequenceMax;
            if (sequence == 0L) {
                timestamp = nextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;
        return ((timestamp - epoch) << timestampLeftShift) |
               (workerId << workerIdShift) |
               sequence;
    }

    @SuppressWarnings("ALL")
    private long nextMillis(long lastTimestamp) {
        long timestamp;
        while ((timestamp = System.currentTimeMillis()) == lastTimestamp) {
            try {
                Thread.sleep(0);
            } catch (InterruptedException ignore) {
            }
        }
        return timestamp;
    }
}
