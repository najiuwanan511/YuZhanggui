package com.yuzhanggui.service;

import com.yuzhanggui.controller.dto.ChatAvatarQueryReqDTO;
import com.yuzhanggui.controller.dto.ChatAvatarQueryRespDTO;

public interface ChatAvatarProfileService {
    ChatAvatarQueryRespDTO query(ChatAvatarQueryReqDTO request);
}
