package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;

@Controller
public class DashboardController {
	private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

	private final InterfaceConfigLoader configLoader;
	private final FileGenerationService fileService;
	@Value("${app.dashboard.auto-token}")
	private String autoToken;
	@Value("${auth.token.header-name}")
	private String tokenHeaderName;

	@Autowired
	public DashboardController(InterfaceConfigLoader configLoader,
							   FileGenerationService fileService) {
		this.configLoader = configLoader;
		this.fileService = fileService;
	}

	@GetMapping("/")
	public String dashboard(Model model, HttpServletResponse response) {
		// Create the security cookie
		Cookie tokenCookie = new Cookie(tokenHeaderName, autoToken);
		tokenCookie.setPath("/");       // Available for whole app
		tokenCookie.setHttpOnly(true);  // CRITICAL: JavaScript cannot access this
		// tokenCookie.setSecure(true); // Uncomment if running on HTTPS
		tokenCookie.setMaxAge(60 * 60 * 24); // Expires in 24 hours
		response.addCookie(tokenCookie);

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
			logger.error("Error loading job history: {}", e.getMessage(), e);
			model.addAttribute("jobs", Collections.emptyList());
			model.addAttribute("error", "Failed to load jobs");
		}

		return "dashboard";
	}
}
