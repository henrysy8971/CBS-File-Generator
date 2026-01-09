package com.silverlakesymmetri.cbs.fileGenerator.security;

import com.silverlakesymmetri.cbs.fileGenerator.entity.DbToken;
import com.silverlakesymmetri.cbs.fileGenerator.repository.DbTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Optional;

@Component
public class TokenValidator {
	private static final Logger logger = LoggerFactory.getLogger(TokenValidator.class);
	private final DbTokenRepository dbTokenRepository;

	@Autowired
	public TokenValidator(DbTokenRepository dbTokenRepository) {
		this.dbTokenRepository = dbTokenRepository;
	}

	public boolean validateToken(String token) {
		if (token == null || token.isEmpty()) {
			logger.warn("Token validation failed: Token is null or empty");
			return false;
		}

		Optional<DbToken> optionalToken = dbTokenRepository.findByTokenValueAndActiveTrue(token);

		if (!optionalToken.isPresent()) {
			logger.warn("Token validation failed: Token not found or inactive - {}", token);
			return false;
		}

		DbToken dbToken = optionalToken.get();

		// Check expiry
		if (dbToken.getExpiryDate() != null &&
				dbToken.getExpiryDate().before(new Timestamp(System.currentTimeMillis()))) {
			logger.warn("Token validation failed: Token expired - {}", token);
			return false;
		}

		// Optimization: Only update DB if last used > 1 minute ago to reduce DB load
		long oneMinuteAgo = System.currentTimeMillis() - 60000;
		if (dbToken.getLastUsedDate() == null || dbToken.getLastUsedDate().getTime() < oneMinuteAgo) {
			dbToken.setLastUsedDate(new Timestamp(System.currentTimeMillis()));
			dbTokenRepository.save(dbToken);
		}

		return true;
	}

	public Optional<DbToken> getToken(String token) {
		return dbTokenRepository.findByTokenValueAndActiveTrue(token);
	}
}
