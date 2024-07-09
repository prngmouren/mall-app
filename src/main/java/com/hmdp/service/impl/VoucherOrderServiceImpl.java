package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private final int SPIN_TIMES=100;
    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断时间
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }

        //3.判断库存
        if (voucher.getStock()<1) {
            return Result.fail("库存不足");
        }
        //一人一单,通过redis分布式锁实现.原子性问题通过lua或者redission解决
        Long userId = UserHolder.getUser().getId();
        SimpleRedisLock lock= new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        boolean isLock=lock.tryLock(1200);
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try{
            return createVoucherOrder(voucherId);
        }
        finally {
            lock.unLock();
        }
    }
    public Result createVoucherOrder(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(count>0){
            return Result.fail("已经购买过了");
        }
        //4/减少库存，通过CAS实现
        int cnt=0;
        boolean success=false;
        while(!success && cnt<SPIN_TIMES) {
            success = seckillVoucherService.update()
                    .setSql("stock = stock-1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            cnt += 1;
        }
        if (!success) {
            return Result.fail("库存不足");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金卷id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }

}
