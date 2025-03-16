package com.berkayb.soundconnect.controller.impl;

import com.berkayb.soundconnect.controller.IFollowController;
import com.berkayb.soundconnect.entity.Follow;
import com.berkayb.soundconnect.service.IFollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static com.berkayb.soundconnect.constant.EndPoints.*;

@RestController
@RequestMapping(FOLLOW)
@RequiredArgsConstructor
public class FollowController implements IFollowController {

}