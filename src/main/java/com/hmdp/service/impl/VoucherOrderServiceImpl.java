package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);//指定返回类型
    }

    //阻塞队列：线程访问队列，队列为空时，线程会阻塞，直到队列里有值
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 异步下单
     */
    @PostConstruct   //在VoucherOrderSeriveImpl这个类初始化完之后，就立即执行init（），因为随时有可能有购买信息
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //线程任务
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }

            }
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 创建订单
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();//这里不是主线程，不能用userHolder取
        //先创建锁对象
        RLock lock = redissonClient.getLock("lock:order" + userId);//这里使用redissonclient可以实现：失败重试，超时自动释放
        //尝试获取锁
        boolean isLock = lock.tryLock();
        //判断是否成功
        if(!isLock){
            //不成功则返回错误
            log.error("不允许重复下单");
        }
        try {
            //@Transactional事务要想生效，就需要代理对象调用，而不是真正的对象。所以这里不能this. 而是要使用代理对象
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();//不管是否成功都要释放锁
        }
    }

    /**
     * 秒杀，存到阻塞队列
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        int r = result.intValue();
        //2.判断返回的结果是否为0
        //3.如果不是，则返回异常信息
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail("不可重复购买");
        }
        //4.如果是则将优惠券id，用户id，订单id 存入阻塞队列（后续进行异步下单）
        //4.1创建订单：获得订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        //4.2创建阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //5.返回订单id
        return Result.ok(orderId);
    }

    /**
     * 创建订单
     *
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //3.2 如果充足，
        //根据优惠券id和用户id查询订单（一人一单）
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        if (count > 0) {
            log.error("一人限购一单");
        }
        // 则扣减库存，创建订单，返回订单id
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)  //乐观锁，高并发时，在修改之前先判断之前查到的库存数是否发生了改变
                .update();//操作数据库
        if (!success) {
            log.error("库存不足");

        }
        save(voucherOrder);//写入数据库
    }
}


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.根据优惠券id查询信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀活动是否开始
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        //2.1 如果没开始，返回异常结果
//        if (beginTime.isAfter(LocalDateTime.now())) {
//            return Result.fail("活动未开始");
//        }
//        if (endTime.isBefore(LocalDateTime.now())) {
//            return Result.fail("活动已经结束");
//        }
//        //2.2 如果开始，查看库存
//        Integer stock = seckillVoucher.getStock();
//        //3. 判断库存是否充足
//        //3.1 如果不充足，返回异常结果
//        if (stock <= 0) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //改变库存可以用乐观锁，但是这里是插入，所以根据userId加悲观锁，串行
//        //事务要先上传，再释放锁
//        //分布式系统，使用分布式锁，加悲观锁不行了，这里的分布式锁用redis实现
//
//        //先创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order" + userId);//这里使用redissonclient可以实现：失败重试，超时自动释放
//        //尝试获取锁
//        boolean isLock = lock.tryLock();
//        //判断是否成功
//        if(!isLock){
//            //不成功则返回错误
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //@Transactional事务要想生效，就需要代理对象调用，而不是真正的对象。所以这里不能this. 而是要使用代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();//不管是否成功都要释放锁
//        }
//    }

//@Transactional
//public Result createVoucherOrder(Long voucherId) {
//    //3.2 如果充足，
//    //根据优惠券id和用户id查询订单（一人一单）
//    Long userId = UserHolder.getUser().getId();
//    int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//    if (count > 0) {
//        return Result.fail("一人限购一单");
//    }
//    // 则扣减库存，创建订单，返回订单id
//    //扣减库存
//    boolean success = seckillVoucherService.update()
//            .setSql("stock = stock - 1")
//            .eq("voucher_id", voucherId).gt("stock", 0)  //乐观锁，高并发时，在修改之前先判断之前查到的库存数是否发生了改变
//            .update();//操作数据库
//    if (!success) {
//        return Result.fail("库存不足");
//
//    }
//    //4.创建订单：获得订单id
//    VoucherOrder voucherOrder = new VoucherOrder();
//    long orderId = redisIdWorker.nextId("order");
//    voucherOrder.setId(orderId);
//    voucherOrder.setVoucherId(voucherId);
//    voucherOrder.setUserId(UserHolder.getUser().getId());
//
//
//    save(voucherOrder);//写入数据库
//    return Result.ok(orderId);
//}






