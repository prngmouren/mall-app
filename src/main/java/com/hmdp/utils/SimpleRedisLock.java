package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.Key;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString()+"-";
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String key=KEY_PREFIX+name;
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
        if(threadId.equals(id)){
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }
    }
}
