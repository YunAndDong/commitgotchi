package com.commitgotchi.report.domain;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class ReportEnqueueCandidateRepository {

    private final ReportEnqueueCandidateMapper mapper;

    public ReportEnqueueCandidateRepository(ReportEnqueueCandidateMapper mapper) {
        this.mapper = mapper;
    }

    public List<ReportEnqueueCandidate> findByTargetDate(LocalDate targetDate) {
        return mapper.findByTargetDate(targetDate);
    }
}
