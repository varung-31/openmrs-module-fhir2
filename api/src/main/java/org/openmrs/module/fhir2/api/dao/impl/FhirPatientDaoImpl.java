/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.dao.impl;

import static org.hibernate.criterion.Restrictions.and;
import static org.hibernate.criterion.Restrictions.eq;
import static org.hibernate.criterion.Restrictions.ilike;
import static org.hibernate.criterion.Restrictions.in;
import static org.hibernate.criterion.Restrictions.or;

import javax.inject.Inject;
import javax.inject.Named;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.StringOrListParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import lombok.AccessLevel;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.MatchMode;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifierType;
import org.openmrs.api.PatientService;
import org.openmrs.module.fhir2.api.dao.FhirPatientDao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Setter(AccessLevel.PACKAGE)
public class FhirPatientDaoImpl extends BaseDaoImpl implements FhirPatientDao {
	
	@Inject
	PatientService patientService;
	
	@Inject
	@Named("sessionFactory")
	SessionFactory sessionFactory;
	
	@Override
	public Patient getPatientByUuid(String uuid) {
		return patientService.getPatientByUuid(uuid);
	}
	
	@Override
	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public PatientIdentifierType getPatientIdentifierTypeByNameOrUuid(String name, String uuid) {
		List<PatientIdentifierType> identifierTypes = (List<PatientIdentifierType>) sessionFactory.getCurrentSession()
		        .createCriteria(PatientIdentifierType.class)
		        .add(or(and(eq("name", name), eq("retired", false)), eq("uuid", uuid))).list();
		
		if (identifierTypes.isEmpty()) {
			return null;
		} else {
			// favour uuid if one was supplied
			if (uuid != null) {
				try {
					return identifierTypes.stream().filter((idType) -> uuid.equals(idType.getUuid())).findFirst().get();
				}
				catch (NoSuchElementException ignored) {}
			}
			
			return identifierTypes.get(0);
		}
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<Patient> findPatientsByName(String name) {
		return patientService.getPatients(name);
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public List<Patient> findPatientsByGivenName(String given) {
		return sessionFactory.getCurrentSession().createCriteria(Patient.class).createAlias("names", "names")
		        .add(ilike("names.givenName", given, MatchMode.START)).list();
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public List<Patient> findPatientsByFamilyName(String family) {
		return sessionFactory.getCurrentSession().createCriteria(Patient.class).createAlias("names", "names")
		        .add(ilike("names.familyName", family, MatchMode.START)).list();
	}

	@Override
	public Collection<Patient> searchForPatients(StringOrListParam name, StringOrListParam given, StringOrListParam family,
			TokenOrListParam identifier, TokenOrListParam gender, DateRangeParam birthDate, DateRangeParam deathDate,
			TokenOrListParam deceased, StringOrListParam city, StringOrListParam state, StringOrListParam postalCode,
			SortSpec sort) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Patient.class);

		handleNames(criteria, name, given, family);
		handleIdentifier(criteria, identifier);
		handleGender("gender", gender).ifPresent(criteria::add);
		handleDateRange("birthdate", birthDate).ifPresent(criteria::add);
		handleDateRange("deathdate", deathDate).ifPresent(criteria::add);
		handleBoolean("dead", deceased).ifPresent(criteria::add);
		handleAddress(criteria, city, state, postalCode);
		handleSort(criteria, sort, this::paramToProp);

		return criteria.list();
	}

	private void handleAddress(Criteria criteria, StringOrListParam city, StringOrListParam state, StringOrListParam postalCode) {
		if (city == null && state == null && postalCode == null) {
			return;
		}
	}

	private void handleIdentifier(Criteria criteria, TokenOrListParam identifier) {
		if (identifier == null) {
			return;
		}

		criteria.createAlias("identifiers", "pi");
		criteria.createAlias("pi.identifierType", "pit");
		criteria.add(eq("pi.retired", false));

		List<Criterion> criterionList = new ArrayList<>();

		List<TokenParam> paramList = identifier.getValuesAsQueryTokens();

		String previousSystem = null;
		List<String> codes = new ArrayList<>();
		for (TokenParam coding : paramList) {
			if (coding.getSystem() != null) {
				if (!coding.getSystem().equals(previousSystem)) {
					if (codes.size() > 0) {
						criterionList.add(and(eq("pit.name", previousSystem), in("pi.identifier", codes)));
						codes.clear();
					}

					previousSystem = coding.getSystem();
				}

				codes.add(coding.getValue());
			} else {
				criterionList.add(eq("pi.identifier", coding.getValue()));
			}

			if (codes.size() > 0) {
				criterionList.add(and(eq("pit.name", previousSystem), in("pi.identifier", codes)));
			}
		}

		criteria.add(or(criterionList.toArray(new Criterion[0])));
	}

	private void handleNames(Criteria criteria, StringOrListParam name, StringOrListParam given, StringOrListParam family) {
		if (name == null && given == null && family == null) {
			return;
		}

		criteria.createAlias("names", "pn");

		if (name != null) {
			List<Criterion> criterionList = new ArrayList<>();

			for (StringParam nameParam : name.getValuesAsQueryTokens()) {
				for (String token : StringUtils.split(nameParam.getValue(), " \t,")) {
					StringParam tokenParam = new StringParam().setValue(token).setExact(nameParam.isExact()).setContains(nameParam.isContains());
					propertyLike("pn.givenName", tokenParam).ifPresent(criterionList::add);
					propertyLike("pn.middle", tokenParam).ifPresent(criterionList::add);
					propertyLike("pn.family", tokenParam).ifPresent(criterionList::add);
				}
			}

			criteria.add(or(criterionList.toArray(new Criterion[0])));
		}

		if (given != null) {
			List<Criterion> criterionList = new ArrayList<>();

			for (StringParam givenName : given.getValuesAsQueryTokens()) {
				propertyLike("pn.givenName", givenName).ifPresent(criterionList::add);
			}

			criteria.add(or(criterionList.toArray(new Criterion[0])));
		}

		if (family != null) {
			List<Criterion> criterionList = new ArrayList<>();

			for (StringParam familyName : family.getValuesAsQueryTokens()) {
				propertyLike("pn.familyName", familyName).ifPresent(criterionList::add);
			}

			criteria.add(or(criterionList.toArray(new Criterion[0])));
		}
	}

	private String paramToProp(String paramName) {
		return null;
	}
}
