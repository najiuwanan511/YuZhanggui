package com.yuzhanggui.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhanggui.entity.XianyuZeroSubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface XianyuZeroSubmissionMapper extends BaseMapper<XianyuZeroSubmission> {
    @Select("SELECT * FROM xianyu_zero_submission WHERE bridge_order_id = #{bridgeId} ORDER BY id ASC")
    List<XianyuZeroSubmission> selectByBridgeId(@Param("bridgeId") Long bridgeId);

    @Select("SELECT COUNT(*) FROM xianyu_zero_submission WHERE bridge_order_id = #{bridgeId} AND pnm_id = #{pnmId}")
    int countMessage(@Param("bridgeId") Long bridgeId, @Param("pnmId") String pnmId);
}
