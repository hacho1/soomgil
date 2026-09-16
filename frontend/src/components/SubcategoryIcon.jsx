import { getSubcategoryIcon } from "../lib/subcategoryIcons";

export default function SubcategoryIcon({ group, topic }) {
  const { Icon, Detail, tone } = getSubcategoryIcon(group, topic);
  return <span className={`subcategory-icon subcategory-tone-${tone}${Detail ? " has-detail" : ""}`} aria-hidden="true">
    <Icon className="subcategory-symbol" size={30} strokeWidth={1.8} />
    {Detail && <span className="subcategory-detail"><Detail size={16} strokeWidth={1.9} /></span>}
  </span>;
}
