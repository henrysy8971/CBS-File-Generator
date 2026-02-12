package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller // Note: @Controller, NOT @RestController
public class DashboardController {

	private final InterfaceConfigLoader configLoader;
	private final FileGenerationService fileService;

	@Autowired
	public DashboardController(InterfaceConfigLoader configLoader, FileGenerationService fileService) {
		this.configLoader = configLoader;
		this.fileService = fileService;
	}

	@GetMapping("/")
	public String dashboard(Model model) {
		// 1. Get List of Interfaces for the Dropdown
		Map<String, ?> configs = configLoader.getEnabledConfigs();
		model.addAttribute("interfaces", configs.keySet());
		PageRequest pageRequest = new PageRequest(0, 20, Sort.Direction.DESC, "createdDate");
		Page<FileGeneration> jobPage = fileService.getAllFiles(pageRequest);
		model.addAttribute("jobs", jobPage.getContent());
		return "dashboard";
	}
}
