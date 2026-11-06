package org.elibanking.walletservice.enquiry;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NameEnquiryRepository extends JpaRepository<NameEnquiry, String> {
}
