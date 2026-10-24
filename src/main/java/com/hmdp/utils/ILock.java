package com.hmdp.utils;

public interface ILock {

    /**
     * Try to acquire the lock.
     *
     * @param timeoutSec lock expiration time in seconds
     * @return true if lock acquired, false otherwise
     */
    boolean tryLock(long timeoutSec);

    /**
     * Release the lock.
     */
    void unlock();
}