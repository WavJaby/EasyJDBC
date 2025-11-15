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

        long generatedWorkerId;
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            NetworkInterface ni = NetworkInterface.getByInetAddress(localHost);
            byte[] hardwareAddress;

            if (ni != null && (hardwareAddress = ni.getHardwareAddress()) != null) {
                generatedWorkerId = (Arrays.hashCode(hardwareAddress) & 0x7FFFFFFF) % workerIdMax;
                logger.info("Worker ID generated from MAC address: {}", generatedWorkerId);
            } else {
                generatedWorkerId = generateFallbackWorkerId(localHost);
                logger.warn("Network interface not available, using fallback Worker ID: {}", generatedWorkerId);
            }
        } catch (Exception e) {
            generatedWorkerId = generateRandomWorkerId();
            logger.warn("Failed to determine network interface, using random Worker ID: {}", generatedWorkerId, e);
        }

        this.workerId = generatedWorkerId;
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

    private long generateFallbackWorkerId(InetAddress localHost) {
        // Use hostname and IP address as fallback
        String hostInfo = localHost.getHostName() + localHost.getHostAddress();
        return (Math.abs(hostInfo.hashCode()) & 0x7FFFFFFF) % workerIdMax;
    }

    private long generateRandomWorkerId() {
        // Last resort: use system properties and current time for pseudo-randomness
        String systemInfo = System.getProperty("user.name", "") + 
                           System.getProperty("java.version", "") + 
                           System.currentTimeMillis();
        return (Math.abs(systemInfo.hashCode()) & 0x7FFFFFFF) % workerIdMax;
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
