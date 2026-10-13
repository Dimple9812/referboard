package com.referboard.tracker.mapper;

import com.referboard.tracker.domain.Application;
import com.referboard.tracker.domain.ApplicationEvent;
import com.referboard.tracker.web.dto.TrackerDtos.CardResponse;
import com.referboard.tracker.web.dto.TrackerDtos.TimelineEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TrackerMapper {

    @Mapping(target = "jobClosed", expression = "java(application.getJobClosedAt() != null)")
    CardResponse toCard(Application application);

    TimelineEntry toEntry(ApplicationEvent event);
}
