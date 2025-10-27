package org.matsim.contrib.rlev;

import org.matsim.contrib.rlev.charging.ChargingModule;
import org.matsim.contrib.rlev.discharging.DischargingModule;
import org.matsim.contrib.rlev.fleet.ElectricFleetModule;
import org.matsim.contrib.rlev.infrastructure.ChargingInfrastructureModule;
import org.matsim.contrib.rlev.stats.EvStatsModule;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.mobsim.qsim.components.QSimComponentsConfigGroup;

import java.util.ArrayList;
import java.util.List;

public class EvBaseModule extends AbstractModule {
	@Override
	public void install() {
		install(new ElectricFleetModule());
		install(new ChargingInfrastructureModule());
		install(new ChargingModule());
		install(new DischargingModule());
		install(new EvStatsModule());

		// Switch on all EV QSim components registered under EvModule.EV_COMPONENT
		QSimComponentsConfigGroup qsimCfg =
				ConfigUtils.addOrGetModule(getConfig(), QSimComponentsConfigGroup.class);

		List<String> activeComponents = new ArrayList<>(qsimCfg.getActiveComponents());
		if (!activeComponents.contains(EvModule.EV_COMPONENT)) {
			activeComponents.add(EvModule.EV_COMPONENT);
			qsimCfg.setActiveComponents(activeComponents);
		}
	}
}
