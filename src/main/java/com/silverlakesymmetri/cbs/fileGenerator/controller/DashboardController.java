package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
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
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;

@Controller
@RequestMapping("/")
public class DashboardController {

	private final InterfaceConfigLoader configLoader;
	private final FileGenerationService fileService;

	@Autowired
	public DashboardController(InterfaceConfigLoader configLoader,
							   FileGenerationService fileService) {
		this.configLoader = configLoader;
		this.fileService = fileService;
	}

	@GetMapping("")
	public String dashboard(Model model) {
		// Get enabled interfaces
		Map<String, ?> configs = configLoader.getEnabledConfigs();
		if (configs == null || configs.isEmpty()) {
			model.addAttribute("interfaces", Collections.emptyList());
		} else {
			model.addAttribute("interfaces", configs.keySet());
		}

		// Get recent jobs
		try {
			PageRequest pageRequest = new PageRequest(0, 20, new Sort(Sort.Direction.DESC, "createdDate"));
			Page<FileGeneration> jobPage = fileService.getAllFiles(pageRequest);
			model.addAttribute("jobs", jobPage.getContent());
		} catch (Exception e) {
			model.addAttribute("jobs", Collections.emptyList());
			model.addAttribute("error", "Failed to load jobs");
		}

		return "dashboard";
	}

	@PostMapping("/auth/set-token")
	public ResponseEntity<?> setToken(@RequestBody Map<String, String> request,
									  HttpServletResponse response) {
		String token = request.get("token");

		if (token == null || token.trim().isEmpty()) {
			return ResponseEntity.badRequest().body("Token cannot be empty");
		}

		if (token.length() < 10) {
			return ResponseEntity.badRequest().body("Token appears invalid");
		}

		// Set secure httpOnly cookie
		Cookie cookie = new Cookie("cbs_auth_token", token);
		cookie.setHttpOnly(true);           // JavaScript cannot access
		cookie.setSecure(true);             // HTTPS only
		cookie.setPath("/");
		cookie.setMaxAge(3600);             // 1 hour expiration

		response.addHeader("Set-Cookie",
				String.format("%s=%s; Max-Age=%d; Path=/; Secure; HttpOnly; SameSite=Strict",
						cookie.getName(), cookie.getValue(), cookie.getMaxAge()));
		response.addCookie(cookie);

		return ResponseEntity.ok().body("Token set");
	}
}
