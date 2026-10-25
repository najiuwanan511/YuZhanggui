package com.yuzhanggui.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhanggui.controller.dto.ChatAvatarQueryReqDTO;
import com.yuzhanggui.controller.dto.ChatAvatarQueryRespDTO;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.entity.XianyuChatUserProfile;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuChatUserProfileMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAvatarProfileServiceImplTest {
    private XianyuAccountMapper accountMapper;
    private XianyuCookieMapper cookieMapper;
    private XianyuChatUserProfileMapper profileMapper;
    private ChatAvatarProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(XianyuAccountMapper.class);
        cookieMapper = mock(XianyuCookieMapper.class);
        profileMapper = mock(XianyuChatUserProfileMapper.class);
        service = new ChatAvatarProfileServiceImpl(accountMapper, cookieMapper, profileMapper, new ObjectMapper());

        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setAvatarUrl("https://img.example/seller.jpg");
        when(accountMapper.selectById(7L)).thenReturn(account);
    }

    @Test
    void returnsPersistentBuyerCacheEvenWhenCookieIsUnavailable() {
        XianyuChatUserProfile cached = new XianyuChatUserProfile();
        cached.setAvatarUrl("https://img.example/buyer.jpg");
        cached.setBuyerUserName("买家甲");
        when(profileMapper.findCachedByBuyer(7L, "buyer-session-1")).thenReturn(cached);

        ChatAvatarQueryRespDTO response = service.query(requestWithSessions("session-1"));

        assertEquals("https://img.example/seller.jpg", response.getAccountAvatarUrl());
        assertEquals("https://img.example/buyer.jpg", response.getBuyerProfiles().get("buyer-session-1").getAvatarUrl());
        verify(profileMapper, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void limitsEachRequestToThreeBuyerProfiles() {
        ChatAvatarQueryReqDTO request = requestWithSessions("1", "2", "3", "4");

        ChatAvatarQueryRespDTO response = service.query(request);

        assertEquals(0, response.getBuyerProfiles().size());
        verify(profileMapper).findCachedByBuyer(7L, "buyer-1");
        verify(profileMapper).findCachedByBuyer(7L, "buyer-2");
        verify(profileMapper).findCachedByBuyer(7L, "buyer-3");
        verify(profileMapper, never()).findCachedByBuyer(7L, "buyer-4");
        assertNull(response.getBuyerProfiles().get("buyer-4"));
    }

    private ChatAvatarQueryReqDTO requestWithSessions(String... sessionIds) {
        ChatAvatarQueryReqDTO request = new ChatAvatarQueryReqDTO();
        request.setXianyuAccountId(7L);
        request.setQueries(List.of(sessionIds).stream().map(sid -> {
            ChatAvatarQueryReqDTO.QueryItem item = new ChatAvatarQueryReqDTO.QueryItem();
            item.setSid(sid);
            item.setBuyerUserId("buyer-" + sid);
            return item;
        }).toList());
        return request;
    }
}
