package com.referboard.referral.mapper;

import com.referboard.referral.domain.Contact;
import com.referboard.referral.domain.ContactStatusHistory;
import com.referboard.referral.domain.JobSnapshot;
import com.referboard.referral.domain.MessageDraft;
import com.referboard.referral.web.dto.ReferralDtos.ContactResponse;
import com.referboard.referral.web.dto.ReferralDtos.DraftResponse;
import com.referboard.referral.web.dto.ReferralDtos.StatusHistoryDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReferralMapper {

    @Mapping(target = "id", source = "contact.id")
    @Mapping(target = "jobId", source = "contact.jobId")
    @Mapping(target = "jobTitle", source = "job.title")
    @Mapping(target = "companyName", source = "job.companyName")
    @Mapping(target = "allowedNextStatuses", expression = "java(contact.getStatus().allowedTargets())")
    @Mapping(target = "createdAt", source = "contact.createdAt")
    ContactResponse toResponse(Contact contact, JobSnapshot job);

    DraftResponse toResponse(MessageDraft draft);

    StatusHistoryDto toDto(ContactStatusHistory history);
}
