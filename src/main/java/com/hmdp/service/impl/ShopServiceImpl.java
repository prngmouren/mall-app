package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryByID(Long id) {
        //缓存穿透queryWithPassThrough(id)

        //互斥锁实现缓存击穿
        Shop shop=queryWithMutex(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    public Shop queryWithMutex(Long id)
    {
        String key= CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判定是否存在
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop= JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        if(shopJson!=null)//防止缓存穿透
        {
            return null;
        }
        //缓存重建
        //获取互斥锁
        String lockKey="lock:shop:"+id;
        Shop shop= null;
        try {
            boolean lock = tryLock(lockKey);
            if(!lock){
                Thread.sleep(50);
                queryWithMutex(id);
            }
            shop = getById(id);
            //5.数据库不存在，返回错误
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6。存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //7.返回数据
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            unLock(lockKey);
        }
        return shop;
    }
    public Shop queryWithPassThrough(Long id){
        String key= CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判定是否存在
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop= JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        if(shopJson!=null)//防止缓存穿透
        {
            return null;
        }
        //4.不存在，查询数据库
        Shop shop=getById(id);
        //5.数据库不存在，返回错误
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6。存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回数据
        return shop;
    }
    private boolean tryLock(String key)
    {
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key)
    {
        stringRedisTemplate.delete(key);
    }
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id =shop.getId();
        if(id==null)
        {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return null;
    }
}
