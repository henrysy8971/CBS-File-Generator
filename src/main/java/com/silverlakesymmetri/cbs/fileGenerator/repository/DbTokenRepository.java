package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.DbToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DbTokenRepository extends JpaRepository<DbToken, Long> {
	Optional<DbToken> findByTokenValueAndActiveTrue(String tokenValue);

	Optional<DbToken> findByApplicationNameAndActiveTrue(String applicationName);
}
