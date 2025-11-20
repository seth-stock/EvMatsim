import xml.etree.ElementTree as ET
import random
import os
import argparse
import pandas as pd
import numpy as np

from rlev.scripts.util import save_xml, get_str

def get_node_coords(network_file):
    tree = ET.parse(network_file)
    root = tree.getroot()
    node_coords = {}

    for node in root.findall(".//node"):
        node_id = node.get("id")
        x = float(node.get("x"))
        y = float(node.get("y"))
        node_coords[node_id] = (x, y)

    return node_coords

def parse_counts_xml(counts_file):
    tree = ET.parse(counts_file)
    root = tree.getroot()
    counts = []

    for count_station in root.findall(".//count"):
        volume = sum(
            int(volume_elem.get("val"))
            for volume_elem in count_station.findall(".//volume")
        )
        counts.append(volume)

    return counts


def _sec_to_time_string(seconds: float) -> str:
    """Convert seconds to HH:MM:SS (wrapped to 24h)."""
    seconds = int(seconds) % (24 * 3600)
    hours = seconds // 3600
    minutes = (seconds % 3600) // 60
    secs = seconds % 60
    return f"{hours:02d}:{minutes:02d}:{secs:02d}"

def create_vehicle_definitions(ids, initial_soc):
    root = ET.Element(
        "vehicleDefinitions",
        attrib={
            "xmlns": "http://www.matsim.org/files/dtd",
            "xmlns:xsi": "http://www.w3.org/2001/XMLSchema-instance",
            "xsi:schemaLocation": "http://www.matsim.org/files/dtd "
            "http://www.matsim.org/files/dtd/"
            "vehicleDefinitions_v2.0.xsd",
        },
    )

    vehicle_type = ET.SubElement(root, "vehicleType", id="EV_65.0kWh")
    ET.SubElement(vehicle_type, "capacity", seats="0", standingRoomInPersons="0")
    ET.SubElement(vehicle_type, "length", meter="7.5")
    ET.SubElement(vehicle_type, "width", meter="1.0")

    engine_info = ET.SubElement(vehicle_type, "engineInformation")
    attributes = ET.SubElement(engine_info, "attributes")

    ET.SubElement(
        attributes,
        "attribute",
        name="HbefaTechnology",
        **{"class": "java.lang.String"},
    ).text = "electricity"
    ET.SubElement(
        attributes,
        "attribute",
        name="chargerTypes",
        **{"class": "java.util.Collections$UnmodifiableCollection"},
    ).text = '["default","dynamic"]'
    ET.SubElement(
        attributes,
        "attribute",
        name="energyCapacityInKWhOrLiters",
        **{"class": "java.lang.Double"},
    ).text = "65.0"

    ET.SubElement(vehicle_type, "costInformation")
    ET.SubElement(vehicle_type, "passengerCarEquivalents", pce="1.0")
    ET.SubElement(vehicle_type, "networkMode", networkMode="car")
    ET.SubElement(vehicle_type, "flowEfficiencyFactor", factor="1.0")

    for id in ids:
        vehicle = ET.SubElement(root, "vehicle", id=str(id), type="EV_65.0kWh")
        attributes = ET.SubElement(vehicle, "attributes")
        ET.SubElement(
            attributes,
            "attribute",
            name="initialSoc",
            **{"class": "java.lang.Double"},
        ).text = str(initial_soc)

    return ET.ElementTree(root)

def create_population_and_plans_xml_counts(
    network_xml_path,
    plans_output,
    vehicles_output,
    num_agents=100,
    counts_path=None,
    population_multiplier=1,
    initial_soc=1,
):
    node_coords = get_node_coords(os.path.abspath(network_xml_path))
    plans_output = os.path.abspath(plans_output)
    vehicles_output = os.path.abspath(vehicles_output)

    plans = ET.Element("plans", attrib={"xml:lang": "de-CH"})
    node_ids = list(node_coords.keys())
    person_ids = []
    person_count = 1

    if counts_path:
        counts_path = os.path.abspath(counts_path)
        counts = parse_counts_xml(counts_path)
    else:
        dist1 = np.random.normal(8, 2.5, num_agents // 2)
        dist2 = np.random.normal(17, 2.5, num_agents - len(dist1))
        dist = np.concatenate([dist1, dist2])
        dist = np.clip(dist, 0, 24)
        bins = np.arange(0, 24)
        counts, _ = np.histogram(dist, bins)

    for i, count in enumerate(counts):
        count = int(get_str(count))
        for _ in range(int(count * population_multiplier)):
            home_node = node_coords[random.choice(node_ids)]
            work_node = node_coords[random.choice(node_ids)]
            errand_node = node_coords[random.choice(node_ids)]

            person = ET.SubElement(plans, "person", id=str(person_count))
            person_ids.append(person_count)
            person_count += 1
            plan = ET.SubElement(person, "plan", selected="yes")

            # Daily schedule (seconds)
            home_depart = ((i + 1) % 24) * 3600 + random.randint(0, 45) * 60
            work_duration = random.uniform(7, 10) * 3600
            errand_duration = random.uniform(1, 3) * 3600
            evening_buffer = random.uniform(1, 2) * 3600

            work_end = home_depart + work_duration
            errand_end = work_end + errand_duration
            final_home_start = min(errand_end + evening_buffer, 23.5 * 3600)

            # Home -> Work
            ET.SubElement(
                plan,
                "act",
                type="home",
                x=str(home_node[0]),
                y=str(home_node[1]),
                end_time=_sec_to_time_string(home_depart),
            )
            ET.SubElement(plan, "leg", mode="car")

            # Work -> Errand
            ET.SubElement(
                plan,
                "act",
                type="work",
                x=str(work_node[0]),
                y=str(work_node[1]),
                end_time=_sec_to_time_string(work_end),
            )
            ET.SubElement(plan, "leg", mode="car")

            # Errand/Leisure stop
            ET.SubElement(
                plan,
                "act",
                type="leisure",
                x=str(errand_node[0]),
                y=str(errand_node[1]),
                end_time=_sec_to_time_string(errand_end),
            )
            ET.SubElement(plan, "leg", mode="car")

            # Return home for the night (final activity keeps open end)
            ET.SubElement(
                plan,
                "act",
                type="home",
                x=str(home_node[0]),
                y=str(home_node[1]),
            )

    vehicle_tree = create_vehicle_definitions(person_ids, initial_soc)
    save_xml(vehicle_tree, vehicles_output)

    tree = ET.ElementTree(plans)
    with open(plans_output, "wb") as f:
        f.write(b'<?xml version="1.0" ?>\n')
        f.write(
            b'<!DOCTYPE plans SYSTEM "http://www.matsim.org/files/dtd/plans_v4.dtd">\n'
        )
        tree.write(f)



def main(args):
    """
    Main function to parse arguments and generate population and plans.

    Args:
        args (argparse.Namespace): Parsed command-line arguments.
    """
    create_population_and_plans_xml_counts(
        args.network,
        args.plans_output,
        args.vehicles_output,
        args.num_agents,
        args.counts_path,
        args.pop_mulitiplier,
        args.percent_home_charge,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Generate population and plans XML files"
    )

    parser.add_argument("network", type=str, help="Input matsim xml network")
    parser.add_argument("plans_output", type=str, help="Output path of plans network")
    parser.add_argument(
        "vehicles_output", type=str, help="Vehicle file used to create vehicles"
    )
    parser.add_argument(
        "--num_agents",
        type=int,
        help="The number of agents to generate",
        default=100,
    )
    parser.add_argument(
        "--counts_path",
        type=str,
        help="Counts file to use for creating population, if none "
        "provided then a bimodal distribution with num_agents "
        "samples will be generated",
        default=None,
    )
    parser.add_argument(
        "--pop_mulitiplier",
        type=float,
        help="How much to mulitply population by based on the counts file",
        default=1,
    )
    parser.add_argument(
        "--percent_home_charge",
        type=float,
        help="Percentage of population that charges at home 0<=p<=1, \
            these will start with full charge at the beginning of the day",
        default=1,
    )

    args = parser.parse_args()
    main(args)




