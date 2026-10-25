package com.yuzhanggui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhanggui.entity.XianyuZeroCallbackEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface XianyuZeroCallbackEventMapper extends BaseMapper<XianyuZeroCallbackEvent> {
    @Select("SELECT COUNT(*) FROM xianyu_zero_callback_event WHERE event_id = #{eventId}")
    int countByEventId(@Param("eventId") String eventId);

    @Select("SELECT COUNT(DISTINCT line_id) FROM xianyu_zero_callback_event WHERE bridge_order_id = #{bridgeId} AND status IN ('完成','失败')")
    int countTerminalLines(@Param("bridgeId") Long bridgeId);

    @Select("SELECT COUNT(*) FROM xianyu_zero_callback_event WHERE bridge_order_id = #{bridgeId} AND status = '失败'")
    int countFailedByBridge(@Param("bridgeId") Long bridgeId);

    @Select("SELECT * FROM xianyu_zero_callback_event WHERE bridge_order_id = #{bridgeId} AND status IN ('完成','失败') ORDER BY id ASC")
    List<XianyuZeroCallbackEvent> selectTerminalEvents(@Param("bridgeId") Long bridgeId);
}
