package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuGoodsInfoMapper;
import com.yuzhanggui.mapper.XianyuItemPolishConfigMapper;
import com.yuzhanggui.mapper.XianyuItemPolishRecordMapper;
import com.yuzhanggui.service.AccountService;
import com.yuzhanggui.service.ItemService;
import com.yuzhanggui.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemPolishServiceImplTest {

    @Test
    void queuesEachActiveAccountInOneManualBatch() {
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuAccount active = new XianyuAccount();
        active.setStatus(1);
        when(accountMapper.selectById(anyLong())).thenReturn(active);

        Executor holdingExecutor = command -> {
            // The test verifies admission and account isolation without running network work.
        };
        ItemPolishServiceImpl service = new ItemPolishServiceImpl(
                accountMapper,
                mock(XianyuGoodsInfoMapper.class),
                mock(XianyuItemPolishConfigMapper.class),
                mock(XianyuItemPolishRecordMapper.class),
                mock(AccountService.class),
                mock(ItemService.class),
                mock(XianyuApiCallUtils.class),
                holdingExecutor);

        Map<String, Object> result = service.startManualRuns(List.of(1L, 2L, 1L));

        assertEquals(2, result.get("requestedCount"));
        assertEquals(2, result.get("startedCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.get("accountResults");
        assertEquals("QUEUED", accounts.get(0).get("status"));
        assertEquals("QUEUED", accounts.get(1).get("status"));
    }
}
