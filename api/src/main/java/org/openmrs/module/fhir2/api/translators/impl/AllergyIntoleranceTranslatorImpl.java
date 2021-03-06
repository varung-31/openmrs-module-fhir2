/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.translators.impl;

import java.util.ArrayList;
import java.util.List;

import lombok.AccessLevel;
import lombok.Setter;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.openmrs.Allergen;
import org.openmrs.AllergenType;
import org.openmrs.Allergy;
import org.openmrs.AllergyReaction;
import org.openmrs.User;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.translators.AllergyIntoleranceCriticalityTranslator;
import org.openmrs.module.fhir2.api.translators.AllergyIntoleranceSeverityTranslator;
import org.openmrs.module.fhir2.api.translators.AllergyIntoleranceTranslator;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.module.fhir2.api.translators.PatientReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.PractitionerReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.ProvenanceTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Setter(AccessLevel.PACKAGE)
public class AllergyIntoleranceTranslatorImpl extends BaseReferenceHandlingTranslator implements AllergyIntoleranceTranslator {
	
	@Autowired
	private PractitionerReferenceTranslator<User> practitionerReferenceTranslator;
	
	@Autowired
	private PatientReferenceTranslator patientReferenceTranslator;
	
	@Autowired
	private ProvenanceTranslator<Allergy> provenanceTranslator;
	
	@Autowired
	private ConceptTranslator conceptTranslator;
	
	@Autowired
	private AllergyIntoleranceSeverityTranslator severityTranslator;
	
	@Autowired
	private AllergyIntoleranceCriticalityTranslator criticalityTranslator;
	
	@Override
	public AllergyIntolerance toFhirResource(Allergy omrsAllergy) {
		if (omrsAllergy == null) {
			return null;
		}
		
		AllergyIntolerance allergy = new AllergyIntolerance();
		allergy.setId(omrsAllergy.getUuid());
		if (omrsAllergy.getAllergen() != null) {
			switch (omrsAllergy.getAllergen().getAllergenType()) {
				case DRUG:
					allergy.addCategory(AllergyIntolerance.AllergyIntoleranceCategory.MEDICATION);
					break;
				case FOOD:
					allergy.addCategory(AllergyIntolerance.AllergyIntoleranceCategory.FOOD);
					break;
				case ENVIRONMENT:
					allergy.addCategory(AllergyIntolerance.AllergyIntoleranceCategory.ENVIRONMENT);
					break;
				case OTHER:
				default:
					return allergy.addCategory(null);
			}
		}
		allergy.setClinicalStatus(setClinicalStatus(omrsAllergy.getVoided()));
		allergy.setPatient(patientReferenceTranslator.toFhirResource(omrsAllergy.getPatient()));
		allergy.setRecorder(practitionerReferenceTranslator.toFhirResource(omrsAllergy.getCreator()));
		allergy.setRecordedDate(omrsAllergy.getDateCreated());
		allergy.getMeta().setLastUpdated(omrsAllergy.getDateChanged());
		allergy.setType(AllergyIntolerance.AllergyIntoleranceType.ALLERGY);
		allergy.setCode(getAllergySubstance(omrsAllergy.getAllergen()));
		allergy.addNote(new Annotation().setText(omrsAllergy.getComment()));
		allergy.setCriticality(
		    criticalityTranslator.toFhirResource(severityTranslator.toFhirResource(omrsAllergy.getSeverity())));
		AllergyIntolerance.AllergyIntoleranceReactionComponent reactionComponent = new AllergyIntolerance.AllergyIntoleranceReactionComponent();
		reactionComponent.setSubstance(getAllergySubstance(omrsAllergy.getAllergen()));
		reactionComponent.setManifestation(getManifestation(omrsAllergy.getReactions()));
		reactionComponent.setSeverity(severityTranslator.toFhirResource(omrsAllergy.getSeverity()));
		allergy.addReaction(reactionComponent);
		allergy.addContained(provenanceTranslator.getCreateProvenance(omrsAllergy));
		allergy.addContained(provenanceTranslator.getUpdateProvenance(omrsAllergy));
		
		return allergy;
	}
	
	@Override
	public Allergy toOpenmrsType(AllergyIntolerance fhirAllergy) {
		return toOpenmrsType(new Allergy(), fhirAllergy);
	}
	
	@Override
	public Allergy toOpenmrsType(Allergy allergy, AllergyIntolerance fhirAllergy) {
		if (fhirAllergy == null) {
			return allergy;
		}
		
		if (fhirAllergy.getId() != null) {
			allergy.setUuid(fhirAllergy.getId());
		}
		
		if (fhirAllergy.hasCode()) {
			if (allergy.getAllergen() == null) {
				Allergen allergen = new Allergen();
				
				allergen.setCodedAllergen(conceptTranslator.toOpenmrsType(fhirAllergy.getCode()));
				allergen.setNonCodedAllergen(fhirAllergy.getCode().getText());
				
				allergy.setAllergen(allergen);
			}
		}
		if (fhirAllergy.hasCategory()) {
			switch (fhirAllergy.getCategory().get(0).getValue()) {
				case MEDICATION:
					allergy.getAllergen().setAllergenType(AllergenType.DRUG);
					break;
				case FOOD:
					allergy.getAllergen().setAllergenType(AllergenType.FOOD);
					break;
				case ENVIRONMENT:
					allergy.getAllergen().setAllergenType(AllergenType.ENVIRONMENT);
					break;
				case BIOLOGIC:
				case NULL:
				default:
					allergy.getAllergen().setAllergenType(null);
			}
		}
		allergy.setVoided(isAllergyInactive(fhirAllergy.getClinicalStatus()));
		allergy.setPatient(patientReferenceTranslator.toOpenmrsType(fhirAllergy.getPatient()));
		allergy.setCreator(practitionerReferenceTranslator.toOpenmrsType(fhirAllergy.getRecorder()));
		
		List<AllergyReaction> reactions = new ArrayList<>();
		
		if (fhirAllergy.hasReaction()) {
			for (AllergyIntolerance.AllergyIntoleranceReactionComponent reaction : fhirAllergy.getReaction()) {
				if (reaction.hasSeverity()) {
					allergy.setSeverity(severityTranslator.toOpenmrsType(reaction.getSeverity()));
				}
				if (reaction.hasManifestation()) {
					reaction.getManifestation().forEach(manifestation -> reactions.add(new AllergyReaction(allergy,
					        conceptTranslator.toOpenmrsType(manifestation), manifestation.getText())));
				}
			}
		}
		if (!fhirAllergy.getNote().isEmpty()) {
			allergy.setComment(fhirAllergy.getNote().get(0).getText());
		}
		allergy.setReactions(reactions);
		
		return allergy;
	}
	
	private CodeableConcept setClinicalStatus(boolean voided) {
		CodeableConcept status = new CodeableConcept();
		if (voided) {
			status.setText("Inactive");
			status.addCoding(
			    new Coding(FhirConstants.ALLERGY_INTOLERANCE_CLINICAL_STATUS_VALUE_SET, "inactive", "Inactive"));
		} else {
			status.setText("Active");
			status.addCoding(new Coding(FhirConstants.ALLERGY_INTOLERANCE_CLINICAL_STATUS_VALUE_SET, "active", "Active"));
		}
		
		return status;
	}
	
	private boolean isAllergyInactive(CodeableConcept status) {
		return status.getCoding().stream()
		        .filter(c -> FhirConstants.ALLERGY_INTOLERANCE_CLINICAL_STATUS_VALUE_SET.equals(c.getSystem()))
		        .anyMatch(c -> "inactive".equals(c.getCode()));
	}
	
	private CodeableConcept getAllergySubstance(Allergen allergen) {
		if (allergen == null) {
			return null;
		}
		
		CodeableConcept allergySubstance = new CodeableConcept();
		
		if (allergen.getCodedAllergen() != null) {
			allergySubstance = conceptTranslator.toFhirResource(allergen.getCodedAllergen());
			allergySubstance.setText(allergen.getNonCodedAllergen());
		}
		
		return allergySubstance;
	}
	
	private List<CodeableConcept> getManifestation(List<AllergyReaction> reactions) {
		List<CodeableConcept> manifestations = new ArrayList<>();
		for (AllergyReaction reaction : reactions) {
			manifestations
			        .add(conceptTranslator.toFhirResource(reaction.getReaction()).setText(reaction.getReactionNonCoded()));
		}
		
		return manifestations;
	}
}
