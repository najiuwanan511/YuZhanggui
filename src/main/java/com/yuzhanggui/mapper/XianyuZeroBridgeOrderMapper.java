package com.yuzhanggui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhanggui.entity.XianyuZeroBridgeOrder;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface XianyuZeroBridgeOrderMapper extends BaseMapper<XianyuZeroBridgeOrder> {
    @Select("SELECT COUNT(*) FROM xianyu_zero_bridge_order WHERE status IN ('SUBMITTING', 'REPLYING')")
    int countOnlineUpdateBlockingTasks();

    @Select("SELECT * FROM xianyu_zero_bridge_order WHERE goods_order_id = #{goodsOrderId} LIMIT 1")
    XianyuZeroBridgeOrder selectByGoodsOrderId(@Param("goodsOrderId") Long goodsOrderId);

    @Select("SELECT * FROM xianyu_zero_bridge_order WHERE xianyu_account_id = #{accountId} AND external_order_id = #{orderId} LIMIT 1")
    XianyuZeroBridgeOrder selectByExternalOrder(@Param("accountId") Long accountId, @Param("orderId") String orderId);

    @Select("SELECT * FROM xianyu_zero_bridge_order WHERE xianyu_account_id = #{accountId} AND sid = #{sid} " +
            "AND buyer_user_id = #{buyerId} AND status = 'WAITING_INPUT' ORDER BY created_time ASC LIMIT 1")
    XianyuZeroBridgeOrder selectWaitingSession(@Param("accountId") Long accountId,
                                               @Param("sid") String sid,
                                               @Param("buyerId") String buyerId);

    @Select("SELECT COUNT(*) FROM xianyu_zero_bridge_order WHERE xianyu_account_id = #{accountId} AND sid = #{sid} " +
            "AND buyer_user_id = #{buyerId} AND status = 'WAITING_INPUT'")
    int countWaitingSession(@Param("accountId") Long accountId, @Param("sid") String sid,
                            @Param("buyerId") String buyerId);

    @Select("SELECT COUNT(*) FROM xianyu_zero_bridge_order WHERE xianyu_account_id = #{accountId} AND sid = #{sid} " +
            "AND buyer_user_id = #{buyerId} AND status NOT IN ('COMPLETED','FAILED')")
    int countActiveSession(@Param("accountId") Long accountId, @Param("sid") String sid,
                           @Param("buyerId") String buyerId);

    @Update("UPDATE xianyu_zero_bridge_order SET collected_count = collected_count + 1, " +
            "status = CASE WHEN collected_count + 1 >= expected_count THEN 'READY' ELSE status END " +
            "WHERE id = #{id} AND status = 'WAITING_INPUT' AND collected_count < expected_count")
    int incrementCollected(@Param("id") Long id);

    @Select("SELECT * FROM xianyu_zero_bridge_order WHERE status IN ('READY','SUBMIT_RETRY') " +
            "AND (next_submit_time IS NULL OR next_submit_time <= NOW(3)) ORDER BY updated_time ASC LIMIT #{limit}")
    List<XianyuZeroBridgeOrder> selectDueSubmissions(@Param("limit") int limit);

    @Update("UPDATE xianyu_zero_bridge_order SET status = 'SUBMITTING', submit_attempts = submit_attempts + 1 " +
            "WHERE id = #{id} AND status IN ('READY','SUBMIT_RETRY') AND (next_submit_time IS NULL OR next_submit_time <= NOW(3))")
    int claimSubmission(@Param("id") Long id);

    @Update("UPDATE xianyu_zero_bridge_order SET status = 'PROCESSING', zero_response = #{response}, " +
            "next_submit_time = NULL, last_error = NULL WHERE id = #{id} AND status = 'SUBMITTING'")
    int markSubmitted(@Param("id") Long id, @Param("response") String response);

    @Update("UPDATE xianyu_zero_bridge_order SET status = 'SUBMIT_RETRY', next_submit_time = #{next}, " +
            "last_error = #{error} WHERE id = #{id} AND status = 'SUBMITTING'")
    int markSubmitRetry(@Param("id") Long id, @Param("next") LocalDateTime next, @Param("error") String error);

    @Select("SELECT * FROM xianyu_zero_bridge_order WHERE status IN ('RESULT_READY','REPLY_RETRY') " +
            "AND (next_reply_time IS NULL OR next_reply_time <= NOW(3)) ORDER BY updated_time ASC LIMIT #{limit}")
    List<XianyuZeroBridgeOrder> selectDueReplies(@Param("limit") int limit);

    @Update("UPDATE xianyu_zero_bridge_order SET status = 'REPLYING', reply_attempts = reply_attempts + 1 " +
            "WHERE id = #{id} AND status IN ('RESULT_READY','REPLY_RETRY') AND (next_reply_time IS NULL OR next_reply_time <= NOW(3))")
    int claimReply(@Param("id") Long id);

    @Update("UPDATE xianyu_zero_bridge_order SET status = #{status}, next_reply_time = NULL, last_error = NULL " +
            "WHERE id = #{id} AND status = 'REPLYING'")
    int markReplySent(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE xianyu_zero_bridge_order SET status = 'REPLY_RETRY', next_reply_time = #{next}, last_error = #{error} " +
            "WHERE id = #{id} AND status = 'REPLYING'")
    int markReplyRetry(@Param("id") Long id, @Param("next") LocalDateTime next, @Param("error") String error);

    @Update("UPDATE xianyu_zero_bridge_order SET status = 'RESULT_READY', result_summary = #{summary}, next_reply_time = NOW(3), last_error = NULL " +
            "WHERE id = #{id} AND status IN ('SUBMITTING','PROCESSING','SUBMITTED')")
    int markResultReady(@Param("id") Long id, @Param("summary") String summary);

    @Update("UPDATE xianyu_zero_bridge_order SET status = 'SUBMIT_RETRY', next_submit_time = NOW(3), last_error = '提交任务中断，已恢复' " +
            "WHERE status = 'SUBMITTING' AND updated_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE)")
    int recoverInterruptedSubmissions();

    @Update("UPDATE xianyu_zero_bridge_order SET status = 'REPLY_RETRY', next_reply_time = NOW(3), last_error = '回复任务中断，已恢复' " +
            "WHERE status = 'REPLYING' AND updated_time < DATE_SUB(NOW(3), INTERVAL 2 MINUTE)")
    int recoverInterruptedReplies();
}
