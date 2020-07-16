/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import ca.uhn.fhir.model.api.Include;
import lombok.NoArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.FhirEncounterService;
import org.openmrs.module.fhir2.api.FhirLocationService;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.FhirPatientService;
import org.openmrs.module.fhir2.api.search.param.PropParam;
import org.openmrs.module.fhir2.api.search.param.SearchParameterMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
@NoArgsConstructor
public class SearchQueryIncludeImpl<U extends IBaseResource> implements SearchQueryInclude<U> {
	
	@Autowired
	private FhirLocationService locationService;
	
	@Autowired
	private FhirObservationService observationService;
	
	@Autowired
	private FhirEncounterService encounterService;
	
	@Autowired
	private FhirPatientService patientService;
	
	@Override
	@SuppressWarnings("unchecked")
	public Set<IBaseResource> getIncludedResources(List<U> resourceList, SearchParameterMap theParams) {
		Set<IBaseResource> includedResourcesSet = new HashSet<>();
		
		List<PropParam<?>> includeParamList = theParams.getParameters(FhirConstants.INCLUDE_SEARCH_HANDLER);
		
		if (CollectionUtils.isEmpty(includeParamList)) {
			return includedResourcesSet;
		}
		
		Set<Include> includeSet = (HashSet<Include>) includeParamList.get(0).getParam();
		includeSet.forEach(includeParam -> {
			switch (includeParam.getParamName()) {
				case FhirConstants.INCLUDE_PART_OF_PARAM:
					includedResourcesSet.addAll(handlePartofInclude(resourceList, includeParam.getParamType()));
					break;
				case FhirConstants.INCLUDE_ENCOUNTER_PARAM:
					includedResourcesSet.addAll(handleEncounterInclude(resourceList, includeParam.getParamType()));
					break;
				case FhirConstants.INCLUDE_PATIENT_PARAM:
					includedResourcesSet.addAll(handlePatientInclude(resourceList, includeParam.getParamType()));
					break;
				case FhirConstants.INCLUDE_HAS_MEMBER_PARAM:
				case FhirConstants.INCLUDE_RESULT_PARAM:
				case FhirConstants.INCLUDE_RELATED_TYPE_PARAM:
					includedResourcesSet.addAll(handleGroupMemberInclude(resourceList, includeParam.getParamType()));
					break;
			}
		});
		
		return includedResourcesSet;
	}
	
	private Set<IBaseResource> handleGroupMemberInclude(List<U> resourceList, String paramType) {
		Set<IBaseResource> includedResources = new HashSet<>();
		Set<String> uniqueObsUUIDs = new HashSet<>();

		switch (paramType) {
			case FhirConstants.OBSERVATION:
				resourceList.forEach(resource -> uniqueObsUUIDs
				        .addAll(getIdsFromReferenceList(((Observation) resource).getHasMember())));
				break;
			case FhirConstants.DIAGNOSTIC_REPORT:
				resourceList.forEach(resource -> uniqueObsUUIDs
						.addAll(getIdsFromReferenceList(((DiagnosticReport) resource).getResult())));
				break;
		}

		uniqueObsUUIDs.removeIf(Objects::isNull);
		uniqueObsUUIDs.forEach(uuid -> includedResources.add(observationService.get(uuid)));

		return includedResources;
	}
	
	private Set<IBaseResource> handlePatientInclude(List<U> resourceList, String paramType) {
		Set<IBaseResource> includedResources = new HashSet<>();
		Set<String> uniquePatientUUIDs = new HashSet<>();
		
		switch (paramType) {
			case FhirConstants.OBSERVATION:
				resourceList.forEach(
				    resource -> uniquePatientUUIDs.add(getIdFromReference(((Observation) resource).getSubject())));
				break;
			case FhirConstants.ALLERGY_INTOLERANCE:
				resourceList.forEach(
				    resource -> uniquePatientUUIDs.add(getIdFromReference(((AllergyIntolerance) resource).getPatient())));
				break;
			case FhirConstants.DIAGNOSTIC_REPORT:
				resourceList.forEach(resource -> uniquePatientUUIDs.add(getIdFromReference(((DiagnosticReport) resource).getSubject())));
				break;
		}
		
		uniquePatientUUIDs.removeIf(Objects::isNull);
		uniquePatientUUIDs.forEach(uuid -> includedResources.add(patientService.get(uuid)));
		
		return includedResources;
	}
	
	private Set<IBaseResource> handleEncounterInclude(List<U> resourceList, String paramType) {
		Set<IBaseResource> includedResources = new HashSet<>();
		Set<String> uniqueEncounterUUIDs = new HashSet<>();

		switch (paramType) {
			case FhirConstants.OBSERVATION:
				resourceList.forEach(
				    resource -> uniqueEncounterUUIDs.add(getIdFromReference(((Observation) resource).getEncounter())));
				break;
			case FhirConstants.DIAGNOSTIC_REPORT:
				resourceList.forEach(resource -> uniqueEncounterUUIDs.add(getIdFromReference(((DiagnosticReport) resource).getEncounter())));
				break;
		}

		uniqueEncounterUUIDs.removeIf(Objects::isNull);
		uniqueEncounterUUIDs.forEach(uuid -> includedResources.add(encounterService.get(uuid)));
		
		return includedResources;
	}
	
	private Set<IBaseResource> handlePartofInclude(List<U> resourceList, String paramType) {
		Set<IBaseResource> includedResources = new HashSet<>();
		
		switch (paramType) {
			case FhirConstants.LOCATION:
				Set<String> uniqueParentLocationUUIDs = new HashSet<>();
				resourceList.forEach(
				    resource -> uniqueParentLocationUUIDs.add(getIdFromReference(((Location) resource).getPartOf())));
				
				uniqueParentLocationUUIDs.removeIf(Objects::isNull);
				uniqueParentLocationUUIDs.forEach(uuid -> includedResources.add(locationService.get(uuid)));
				break;
		}
		
		return includedResources;
	}
	
	private static List<String> getIdsFromReferenceList(List<Reference> referenceList) {
		List<String> idList = new ArrayList<>();
		
		if (referenceList != null) {
			referenceList.forEach(reference -> idList.add(getIdFromReference(reference)));
		}
		
		return idList;
	}
	
	private static String getIdFromReference(Reference reference) {
		return reference != null ? reference.getReferenceElement().getIdPart() : null;
	}
	
}
