package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;

@Controller
public class DashboardController {
	private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

	private final InterfaceConfigLoader configLoader;
	private final FileGenerationService fileService;

	@Autowired
	public DashboardController(InterfaceConfigLoader configLoader,
							   FileGenerationService fileService) {
		this.configLoader = configLoader;
		this.fileService = fileService;
	}

	@GetMapping("/")
	public String dashboard(Model model) {
		// Get enabled interfaces
		Map<String, ?> configs = configLoader.getEnabledConfigs();
		model.addAttribute("interfaces", (configs != null && !configs.isEmpty()) ? configs.keySet() : Collections.emptyList());

		// Get recent jobs
		try {
			Sort sort = new Sort(Sort.Direction.DESC, "createdDate");
			PageRequest pageRequest = new PageRequest(0, 20, sort);
			Page<FileGeneration> jobPage = fileService.getAllFiles(pageRequest);
			model.addAttribute("jobs", jobPage.getContent());
		} catch (Exception e) {
			logger.error("Error loading job history: {}", e.getMessage());
			model.addAttribute("jobs", Collections.emptyList());
			model.addAttribute("error", "Failed to load jobs");
		}

		return "dashboard";
	}

	@PostMapping("/cbs-file-generator/api/v1/auth/set-token")
	public ResponseEntity<?> setToken(@RequestBody Map<String, String> request,
									  HttpServletResponse response) {
		String token = request.get("token");

		if (token == null || token.trim().isEmpty()) {
			return ResponseEntity.badRequest().body("Token cannot be empty");
		}

		if (token.length() < 10) {
			return ResponseEntity.badRequest().body("Token appears invalid");
		}

		String cookieHeader = String.format(
				"cbs_auth_token=%s; Max-Age=%d; Path=/; Secure; HttpOnly; SameSite=Strict",
				token, 3600
		);

		response.addHeader("Set-Cookie", cookieHeader);
		logger.info("Secure token cookie set for session");
		return ResponseEntity.ok().body(Collections.singletonMap("status", "Token saved securely"));
	}
}
