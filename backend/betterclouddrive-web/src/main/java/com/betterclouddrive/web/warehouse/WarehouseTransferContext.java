package com.betterclouddrive.web.warehouse;

import com.betterclouddrive.common.dto.WarehouseEvent.Channel;
import com.betterclouddrive.common.dto.WarehouseEvent.Mode;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Set only after the existing download authorization has resolved the resource. */
public record WarehouseTransferContext(Long actorId, Long teamId, Long resourceId, Channel channel, Mode mode) {
    public static final String ATTRIBUTE = WarehouseTransferContext.class.getName();

    public static void bind(Long actorId, Long teamId, Long resourceId, Channel channel, Mode mode) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(ATTRIBUTE,
                    new WarehouseTransferContext(actorId, teamId, resourceId, channel, mode));
        }
    }
}
